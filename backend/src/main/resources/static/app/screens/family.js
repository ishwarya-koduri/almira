/* Family — the roster, and what each person can see.
   Note what is deliberately absent: another member's private count or values.
   You see who is here and what they have shared with you, nothing more.

   Each person is an avatar in their own colour, with their role in plain words
   (X-56). "What Ravi sees" previews Home through his eyes, built by the server
   from what you can already see: what he sees too, and what he does not. His own
   private records are never in it, because they were never read. */

import { api } from "../api.js";
import {
  el, mount, sheet, field, textInput, select, withBusy, toast, empty, segmented, avatar, categoryIcon,
  skeletonRows, notice,
} from "../ui.js";
import { roleKey } from "../glance.js";
import { state } from "../state.js";
import { reload } from "../app.js";
import {
  loadFamilyLifecycle, memorialNotice, memberLifecycle, leaveCard, successorCard, welcomeCard, dormancyCard,
} from "../lifecycle.js";
import { t, localDate } from "../i18n.js";
import { confirmItsYou } from "../step-up.js";

/** Under eighteen on the day, by the same rule as app.is_minor in the database. */
function isMinorOn(dateOfBirth, today = new Date()) {
  if (!dateOfBirth) return false;
  const adult = new Date(dateOfBirth);
  adult.setFullYear(adult.getFullYear() + 18);
  return adult > today;
}

export async function familyScreen(host) {
  const members = state.members;
  // Adding and inviting people is membership, frozen while a household is dormant (docs/05 §12.7).
  const canManage = ["owner", "admin"].includes(state.household.myRole) && !state.household.readOnly
    && !state.household.dormant;
  // Leaving, a successor, a memorial, coming of age (docs/05 §12), and the
  // parental consents a child's records rest on. Each part fails on its own
  // and the roster draws regardless.
  const [lifecycle, consents] = await Promise.all([
    loadFamilyLifecycle(),
    api.parentalConsents(state.household.id).catch(() => []),
  ]);
  const liveConsent = (memberId) => consents.find((c) => c.memberId === memberId && !c.withdrawnAt);
  const me = members.find((member) => member.isMe);

  mount(host, el("div.stack", {},
    state.household.readOnly && me && memorialNotice(me),
    dormancyCard(lifecycle),
    el("div.row-between.wrap", {},
      el("div", {},
        el("h1", {}, state.household.name),
        el("p.muted", { style: { margin: 0 } },
          t(members.length === 1 ? "family.count.one" : "family.count.many", { count: members.length })),
      ),
      canManage && el("button.btn.btn-primary", { type: "button", onclick: () => addMember() }, t("family.add")),
    ),

    el("div.card.card-tight", {},
      el("div.list", {}, ...members.map((member) => {
        const { meta, actions } = memberLifecycle(member, lifecycle);
        return el("div.list-row.wrap", { style: { cursor: "default" } },
          avatar(member.id, member.displayName, { size: "md" }),
          el("div.grow", {},
            el("div.title", {}, member.displayName, member.isMe && el("span.pill", { style: { marginLeft: "8px" } }, t("family.you"))),
            el("div.meta", {}, [
              relationshipText(member),
              member.isMinor && t("family.minor"),
              t(roleKey(member)),
              ...meta,
            ].filter(Boolean).join(" · ")),
            member.isMinor && member.isManaged && !member.passedAway && consentLine(member),
          ),
          el("div.lifecycle-actions", {},
            !member.isMe && el("button.btn.btn-sm", {
              type: "button", onclick: () => openPreview(member),
            }, t("family.preview.open", { name: member.displayName })),
            canManage && member.isManaged && !member.passedAway && el("button.btn.btn-sm", {
              type: "button", onclick: () => invite(member),
              "aria-label": t("family.inviteRow", { name: member.displayName }),
            }, t("family.invite")),
            ...actions,
          ),
        );
      })),
    ),

    // A notice, not a coloured box (X-52).
    notice(el("span", {},
      el("b", {}, t("family.privacy.lead")), " ", t("family.privacy.body"),
    ), { role: "note" }),

    welcomeCard(lifecycle),
    !state.household.readOnly && successorCard(lifecycle, members),
    !state.household.readOnly && leaveCard(lifecycle),
  ));

  /** "Spouse", not "spouse"; "self" is already said by the You pill. */
  function relationshipText(member) {
    if (!member.relationship || member.relationship === "self") return null;
    const key = `family.relationship.${member.relationship}`;
    const text = t(key);
    return text === key ? member.relationship : text;
  }

  /**
   * "What Ravi sees" (X-56). Everything in it is something the viewer can
   * already see; the server decides, for each, whether Ravi sees it too.
   */
  function openPreview(member) {
    const body = el("div.stack-3", {}, skeletonRows(3));
    sheet({ title: t("family.preview.title", { name: member.displayName }), body, wide: true });
    (async () => {
      let preview;
      try {
        preview = await api.memberPreview(state.household.id, member.id);
      } catch (error) {
        mount(body, notice(error.message, { tone: "alert" }));
        return;
      }
      const name = preview.displayName;
      const recordRow = (record, absent) => el(`div.list-row${absent ? ".preview-absent" : ""}`, { style: { cursor: "default" } },
        record.recordType === "investment"
          ? categoryIcon(record.categoryCode, record.color)
          : el("span.pill.pill-caution", {}, t("family.preview.owed")),
        el("div.grow", { style: { minWidth: 0 } },
          el("div.title", {}, record.title),
          el("div.meta", {}, absent
            ? t(`family.preview.why.${record.visibility}`, { name })
            : categoryName(record.categoryCode, record.categoryLabel))),
        !absent && record.valueFormatted && el("div.amount", {},
          record.recordType === "liability" ? el("span.owed", {}, `− ${record.valueFormatted}`) : el("b", {}, record.valueFormatted)));

      mount(body,
        el("div.row", {},
          avatar(member.id, name, { size: "md" }),
          el("p", { style: { margin: 0 } }, preview.explanation)),
        preview.canSignIn && el("div.preview-frame", { "aria-label": t("family.preview.frame", { name }) },
          el("div.overline", {}, t("family.preview.homeFor", { name })),
          el("div.detail-value", {}, preview.sharedAssetsFormatted),
          el("div.caption.muted", {}, t("family.preview.figure", { name })),
          preview.sharedOwedFormatted && el("div.caption.muted", {},
            t("family.preview.owedFigure", { amount: preview.sharedOwedFormatted })),
          preview.sees.length
            ? el("div.list", {}, ...preview.sees.map((record) => recordRow(record, false)))
            : el("p.caption.muted", { style: { margin: 0 } }, t("family.preview.nothingShared", { name })),
        ),
        preview.notInTheirView.length > 0 && el("div.stack-2", {},
          el("div.overline", {}, t("family.preview.notInView", { name, count: preview.notInTheirView.length })),
          el("div.list", {}, ...preview.notInTheirView.map((record) => recordRow(record, true)))),
        el("details", {},
          el("summary.caption", {}, t("family.preview.caveats")),
          // Server sentences stay English (Doc 14).
          ...preview.caveats.map((caveat) => el("p.caption.muted", {}, caveat))),
      );
    })();
  }

  /**
   * The dated line on a child's profile: whose consent their records rest on,
   * or plainly that there is none yet (DPDP s.9; docs/05 §6).
   */
  function consentLine(member) {
    const consent = liveConsent(member.id);
    if (!consent) {
      return el("div.caption.row.wrap", {},
        el("span", {}, t("family.consent.none")),
        el("button.btn.btn-ghost.btn-sm", { type: "button", onclick: () => recordConsent(member) },
          t("family.consent.record")));
    }
    const withdraw = consent.givenByMe &&
      el("button.btn.btn-ghost.btn-sm", { type: "button" }, t("family.consent.withdraw"));
    if (withdraw) {
      withdraw.onclick = () => withBusy(withdraw, async () => {
        try {
          await api.withdrawParentalConsent(state.household.id, consent.id);
          toast(t("family.consent.withdrawn"));
          await familyScreen(host);
        } catch (error) { toast(error.message, { tone: "error" }); }
      });
    }
    return el("div.caption.row.wrap", {},
      el("span", {}, t(`family.consent.line.${consent.capacity}`, {
        name: consent.givenByName || t("family.consent.someone"), date: localDate(consent.givenAt),
      })),
      withdraw);
  }

  /**
   * "You're adding Aarav's records as their parent." One declaration and one
   * code, once; then the member and the consent are saved together. Closing the
   * code sheet saves nothing.
   */
  function parentalConsentSheet(childName, onConsent) {
    let capacity = "parent";
    const capacityHost = el("div", {});
    const drawCapacity = () => mount(capacityHost, segmented(
      [{ value: "parent", label: t("family.consent.parent") },
       { value: "lawful_guardian", label: t("family.consent.guardian") }],
      capacity, (value) => { capacity = value; drawCapacity(); }));
    drawCapacity();

    const declare = el("input", { type: "checkbox", id: "parental-declaration" });
    const declareRow = el("label.consent-declaration", { for: "parental-declaration" },
      declare, el("span", {}, t("family.consent.declaration", { name: childName })));
    const error = el("div.help.error", { role: "alert", style: { minHeight: "1.15rem" } });
    const go = el("button.btn.btn-primary", { type: "button" }, t("family.consent.continue"));

    const modal = sheet({
      title: t("family.consent.title", { name: childName }),
      body: el("div.stack-3", {},
        el("p", { style: { margin: 0 } }, t("family.consent.body", { name: childName })),
        capacityHost,
        declareRow,
        el("p.notice-line", {},
          el("span.notice-mark", { "aria-hidden": "true" }, "ⓘ"),
          el("span", {}, t("family.consent.check"))),
        error,
      ),
      footer: [go],
    });

    go.onclick = () => withBusy(go, async () => {
      error.textContent = "";
      if (!declare.checked) { error.textContent = t("family.consent.declareFirst"); return; }
      try {
        if (!(await confirmItsYou(t("family.consent.whyConfirm")))) return;
        await onConsent(capacity);
        modal.close();
      } catch (apiError) { error.textContent = apiError.message; }
    });
  }

  function recordConsent(member) {
    parentalConsentSheet(member.displayName, async (capacity) => {
      await api.giveParentalConsent(state.household.id, member.id, { capacity, confirmAdult: true });
      toast(t("family.consent.recorded", { name: member.displayName }));
      await familyScreen(host);
    });
  }

  function addMember() {
    const name = textInput({ placeholder: t("common.theirName") });
    const relationship = select({
      options: [
        { value: "spouse", label: t("family.relationship.spouse") }, { value: "child", label: t("family.relationship.child") },
        { value: "parent", label: t("family.relationship.parent") }, { value: "sibling", label: t("family.relationship.sibling") },
        { value: "other", label: t("family.relationship.someoneElse") },
      ],
    });
    const dob = textInput({ type: "date" });
    const diedOn = textInput({ type: "date" });
    const nameField = field({ label: t("capture.field.title"), control: name, required: true });

    const save = el("button.btn.btn-primary", { type: "button" }, t("family.addButton"));
    const modal = sheet({
      title: t("family.addTitle"),
      body: el("div.stack-3", {},
        nameField,
        field({ label: t("family.relationshipToYou"), control: relationship }),
        field({ label: t("family.dob"), control: dob, help: t("family.dobHelp") }),
        field({ label: t("family.diedOn"), control: diedOn, help: t("family.diedOnHelp") }),
      ),
      footer: [save],
    });

    const create = () => api.addMember(state.household.id, {
      displayName: name.value.trim(),
      relationship: relationship.value,
      dateOfBirth: dob.value || null,
      diedOn: diedOn.value || null,
    });

    save.onclick = () => withBusy(save, async () => {
      if (!name.value.trim()) { nameField.setError(t("family.nameMissing")); return; }
      // A child's records rest on a parent's consent, so that comes first and
      // nothing is saved without it.
      if (isMinorOn(dob.value)) {
        const childName = name.value.trim();
        let member = null;   // kept, so a retry after a failed consent adds no second child
        parentalConsentSheet(childName, async (capacity) => {
          member = member || await create();
          await api.giveParentalConsent(state.household.id, member.id, { capacity, confirmAdult: true });
          modal.close();
          toast(t("family.consent.recorded", { name: childName }));
          await reload();
        });
        return;
      }
      try {
        await create();
        modal.close();
        toast(t("family.added", { name: name.value.trim() }));
        await reload();
      } catch (error) { nameField.setError(error.message); }
    });
  }

  function invite(member) {
    const phone = textInput({ type: "tel", placeholder: "98765 43210" });
    const role = select({
      options: [
        { value: "editor", label: t("family.invite.role.editor") },
        { value: "admin", label: t("family.invite.role.admin") },
        { value: "viewer", label: t("family.invite.role.viewer") },
      ],
      value: "editor",
    });
    const phoneField = field({ label: t("family.invite.phone"), control: phone, required: true,
      help: t("family.invite.phoneHelp") });

    const send = el("button.btn.btn-primary", { type: "button" }, t("family.invite.create"));
    const modal = sheet({
      title: t("family.inviteWho", { name: member.displayName }),
      body: el("div.stack-3", {},
        el("p.caption.muted", {}, t("family.invite.explain", { name: member.displayName })),
        phoneField,
        field({ label: t("family.invite.roleLabel"), control: role, help: t("family.invite.roleHelp") }),
      ),
      footer: [send],
    });

    send.onclick = () => withBusy(send, async () => {
      if (!phone.value.trim()) { phoneField.setError(t("family.invite.phoneMissing")); return; }
      try {
        const invitation = await api.invite(state.household.id, {
          memberId: member.id, phone: phone.value.trim(), role: role.value,
        });
        modal.close();
        showLink(member, invitation);
      } catch (error) { phoneField.setError(error.message); }
    });
  }

  /**
   * Shown once, and only here. The token is the credential: after this sheet
   * closes the server holds nothing but its hash, so there is no way to look
   * it up again — a new invitation is the only recovery.
   */
  function showLink(member, invitation) {
    const link = `${location.origin}/#/invite/${invitation.token}`;
    const input = textInput({ value: link, readOnly: true, "aria-label": t("family.invite.link") });
    const copy = el("button.btn", { type: "button" }, t("family.invite.copy"));
    copy.onclick = async () => {
      try { await navigator.clipboard.writeText(link); toast(t("sharing.copied")); }
      catch { input.select(); }
    };
    sheet({
      title: t("family.invite.for", { name: member.displayName }),
      body: el("div.stack-3", {},
        el("p.caption.muted", {}, t("family.invite.sendLink")),
        input,
      ),
      footer: [copy],
    });
  }
}
