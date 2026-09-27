package tech.almira.config

import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Refuses to start when the role that serves requests can get past row-level
 * security.
 *
 * Row-level security is the privacy model: one member's private records are
 * unreadable to another because a policy says so, not because the application
 * remembered to filter. PostgreSQL skips those policies for a superuser, for a
 * role with BYPASSRLS, and for the owner of a table (and for anyone holding the
 * owner's privileges through membership). A runtime role that is any of those —
 * or that can `SET ROLE` to one — serves every member every record, and nothing
 * looks wrong: every request answers, every screen renders. Until this existed,
 * production started regardless and only `/health` said so, to whoever looked.
 * It is a refusal for the same reason as the JWT secret and page checksums.
 *
 * **What counts.** It asks, *as the runtime role*, which roles it is or is a
 * member of (`pg_has_role(current_user, …, 'MEMBER')`, which is transitive and
 * includes itself), and refuses if any one of them is
 *
 *  - a superuser;
 *  - BYPASSRLS;
 *  - CREATEROLE — on PostgreSQL before 16 that can grant itself membership of
 *    any non-superuser role, the owner included, so it is one statement away
 *    from the rest of this list;
 *  - the owner of a table with row-level security enabled;
 *
 * or if the runtime role has the owner's name. The refusal names the role and
 * the attribute, and says whether it is the runtime role itself or a role it can
 * become.
 *
 * **When.** Called from [DatabaseConfig] before Flyway migrates, so a refused
 * start has written nothing, and once more after migrating, before the web
 * server accepts a connection — a migration runs as the owner and could hand a
 * table to the runtime role, and that is refused before anybody is served.
 *
 * **No relaxation, anywhere.** Unlike page checksums there is no development
 * or test exception: the development stack and the test suite are both built on
 * the same two-role split as production (dev-personal/postgres-init,
 * testcontainers-init.sql), and a suite running as a role that bypasses the
 * policies would pass while production leaked. A check that cannot read the
 * catalogue refuses too.
 */
class RuntimeRoleCheck(private val props: AlmiraProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** [runtime] is the pool that serves requests — the one connecting as `almira.db.app-user`. */
    fun verify(runtime: DataSource, stage: String) {
        val (roles, readError) = try {
            runtime.connection.use { connection ->
                connection.prepareStatement(ROLES_SQL).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Role(
                                        name = rs.getString("rolname"),
                                        isSelf = rs.getBoolean("is_self"),
                                        superuser = rs.getBoolean("rolsuper"),
                                        bypassRls = rs.getBoolean("rolbypassrls"),
                                        createRole = rs.getBoolean("rolcreaterole"),
                                        ownedRlsTables = rs.getString("owned_rls_tables")
                                            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                                            .orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }
            } to null
        } catch (e: SQLException) {
            null to e
        }

        when (val verdict = decide(props.db.appUser, props.db.ownerUser, roles)) {
            Verdict.Enforced -> log.info(
                "Runtime database role {} cannot bypass row-level security ({})", props.db.appUser, stage,
            )
            is Verdict.Refuse -> throw IllegalStateException(
                "Refusing to start ($stage) — ${verdict.reason}",
                readError,
            )
        }
    }

    data class Role(
        val name: String,
        val isSelf: Boolean,
        val superuser: Boolean = false,
        val bypassRls: Boolean = false,
        val createRole: Boolean = false,
        val ownedRlsTables: List<String> = emptyList(),
    )

    sealed interface Verdict {
        data object Enforced : Verdict
        data class Refuse(val reason: String) : Verdict
    }

    companion object {
        /**
         * Every role the connected role is or can become, with the attributes
         * that skip row-level security. Readable by any role: pg_roles and
         * pg_class are public catalogues.
         */
        const val ROLES_SQL = """
            select r.rolname,
                   r.rolname = current_user as is_self,
                   r.rolsuper, r.rolbypassrls, r.rolcreaterole,
                   (select string_agg(n.nspname || '.' || c.relname, ',' order by n.nspname, c.relname)
                      from pg_class c join pg_namespace n on n.oid = c.relnamespace
                     where c.relowner = r.oid and c.relrowsecurity) as owned_rls_tables
              from pg_roles r
             where pg_has_role(current_user, r.oid, 'MEMBER')
             order by (r.rolname = current_user) desc, r.rolname
        """

        private const val WHY =
            "Row-level security is the privacy model; a runtime role that can get past it serves every " +
                "member every record, and nothing else looks wrong. Run scripts/bootstrap-prod-db.sh " +
                "(deploy/bootstrap-db.sql) to create a non-owner role with NOSUPERUSER NOBYPASSRLS " +
                "NOCREATEROLE, and point ALMIRA_DB_APP_USER at it. See docs/17 §3."

        /**
         * The whole decision, with no database. [roles] is what [ROLES_SQL]
         * returned, or null if it could not be read.
         */
        fun decide(appUser: String, ownerUser: String, roles: List<Role>?): Verdict {
            if (appUser == ownerUser) {
                return Verdict.Refuse(
                    "the runtime database role '$appUser' (ALMIRA_DB_APP_USER) is the schema owner " +
                        "(ALMIRA_DB_OWNER_USER), which bypasses the row-level security of every table it " +
                        "owns. $WHY",
                )
            }
            if (roles == null) {
                return Verdict.Refuse(
                    "whether the runtime database role '$appUser' can bypass row-level security could " +
                        "not be read, and an unread role is not assumed safe. $WHY",
                )
            }
            val self = roles.firstOrNull { it.isSelf }
            if (self == null || self.name != appUser) {
                return Verdict.Refuse(
                    "the runtime pool connected as '${self?.name ?: "an unknown role"}', not as " +
                        "ALMIRA_DB_APP_USER '$appUser'. $WHY",
                )
            }
            val problems = roles.flatMap { role ->
                val who = if (role.isSelf) "the runtime database role '${role.name}' (ALMIRA_DB_APP_USER)"
                else "the runtime database role '$appUser' is a member of '${role.name}' (so can SET ROLE to it), which"
                buildList {
                    if (role.superuser) add("$who is a SUPERUSER")
                    if (role.bypassRls) add("$who has BYPASSRLS")
                    if (role.createRole) add("$who has CREATEROLE, which can grant itself any non-superuser role")
                    if (role.ownedRlsTables.isNotEmpty()) {
                        val shown = role.ownedRlsTables.take(5).joinToString(", ") +
                            if (role.ownedRlsTables.size > 5) " and ${role.ownedRlsTables.size - 5} more" else ""
                        add("$who owns tables under row-level security ($shown), and an owner bypasses their policies")
                    }
                }
            }
            if (problems.isEmpty()) return Verdict.Enforced
            return Verdict.Refuse(problems.joinToString("; ") + ". $WHY")
        }
    }
}
