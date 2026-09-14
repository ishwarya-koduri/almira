/* Family — the roster, and what each person can see.
   Note what is deliberately absent: another member's private count or values.
   You see who is here and what they have shared with you, nothing more. */

import { api } from "../api.js";
import { el, mount, sheet, field, textInput, select, withBusy, toast, empty, segmented } from "../ui.js";
import { state } from "../state.js";
import { reload } from "../app.js";
import {
  loadFamilyLifecycle, memorialNotice, memberLifecycle, leaveCard, successorCard, welcomeCard,
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
  const canManage = ["owner", "admin"].includes(state.household.myRole) && !state.household.readOnly;
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
    el("div.row-between.wrap", {},
      el("div", {},
        el("h1", {}, state.household.name),
        el("p.muted", { style: { margin: 0 } },
          `${members.length} ${members.length === 1 ? "person" : "people"}`),
      ),
      canManage && el("button.btn.btn-primary", { type: "button", onclick: () => addMember() }, "＋ Add someone"),
    ),

    el("div.card.card-tight", {},
      el("div.list", {}, ...members.map((member) => {
        const { meta, actions } = memberLifecycle(member, lifecycle);
        return el("div.list-row.wrap", { style: { cursor: "default" } },
          el("div.grow", {},
            el("div.title", {}, member.displayName, member.isMe && el("span.pill", { style: { marginLeft: "8px" } }, "You")),
            el("div.meta", {}, [
              member.relationship,
              member.isMinor && "minor",
              member.isManaged ? "no login yet" : member.role,
              ...meta,
            ].filter(Boolean).join(" · ")),
            member.isMinor && member.isManaged && !member.passedAway && consentLine(member),
          ),
          el("div.lifecycle-actions", {},
            canManage && member.isManaged && !member.passedAway && el("button.btn.btn-sm", {
              type: "button", onclick: () => invite(member),
            }, "Invite to sign in"),
            ...actions,
          ),
        );
      })),
    ),

    el("div.banner.banner-accent", {},
      el("div", {},
        el("b", {}, "Everyone keeps their own privacy. "),
        "A role decides what someone can ", el("i", {}, "do"), " — invite people, edit shared entries. ",
        "It never decides what they can ", el("i", {}, "see"), ". ",
        "Private entries stay private, including from the household owner.",
      ),
    ),

    welcomeCard(lifecycle),
    !state.household.readOnly && successorCard(lifecycle, members),
    !state.household.readOnly && leaveCard(lifecycle),
  ));

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
    const error = el("div.help.error", { style: { minHeight: "1.15rem" } });
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
    const name = textInput({ placeholder: "Their name", "aria-label": "Name" });
    const relationship = select({
      options: [
        { value: "spouse", label: "Spouse" }, { value: "child", label: "Child" },
        { value: "parent", label: "Parent" }, { value: "sibling", label: "Sibling" },
        { value: "other", label: "Someone else" },
      ],
      "aria-label": "Relationship",
    });
    const dob = textInput({ type: "date", "aria-label": "Date of birth" });
    const diedOn = textInput({ type: "date", "aria-label": t("family.diedOn") });
    const nameField = field({ label: "Name", control: name, required: true });

    const save = el("button.btn.btn-primary", { type: "button" }, "Add");
    const modal = sheet({
      title: "Add someone",
      body: el("div.stack-3", {},
        nameField,
        field({ label: "Relationship to you", control: relationship }),
        field({ label: "Date of birth", control: dob,
          help: "Optional. Helps us flag accounts held for a minor." }),
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
      if (!name.value.trim()) { nameField.setError("Give this person a name"); return; }
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
        toast(`${name.value.trim()} added.`);
        await reload();
      } catch (error) { nameField.setError(error.message); }
    });
  }

  function invite(member) {
    const phone = textInput({ type: "tel", placeholder: "98765 43210", "aria-label": "Phone number" });
    const role = select({
      options: [
        { value: "editor", label: "Editor — can add and edit shared entries" },
        { value: "admin", label: "Admin — can also manage people" },
        { value: "viewer", label: "Viewer — can only look" },
      ],
      value: "editor",
      "aria-label": "Role",
    });
    const phoneField = field({ label: "Their phone number", control: phone, required: true,
      help: "They'll sign in with this number." });

    const send = el("button.btn.btn-primary", { type: "button" }, "Create invitation");
    const modal = sheet({
      title: `Invite ${member.displayName}`,
      body: el("div.stack-3", {},
        el("p.caption.muted", {},
          `When ${member.displayName} accepts, they take over this entry rather than ` +
          "becoming a second person — so nothing they own gets split in two."),
        phoneField,
        field({ label: "What should they be able to do?", control: role,
          help: "This does not give them sight of anyone's private entries." }),
      ),
      footer: [send],
    });

    send.onclick = () => withBusy(send, async () => {
      if (!phone.value.trim()) { phoneField.setError("Enter their phone number"); return; }
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
    const input = textInput({ value: link, readOnly: true, "aria-label": "Invitation link" });
    const copy = el("button.btn", { type: "button" }, "Copy link");
    copy.onclick = async () => {
      try { await navigator.clipboard.writeText(link); toast("Link copied."); }
      catch { input.select(); }
    };
    sheet({
      title: `Invitation for ${member.displayName}`,
      body: el("div.stack-3", {},
        el("p.caption.muted", {},
          "Send them this link. It works once and expires in 14 days. " +
          "We only store a hash of it, so this is the only time you'll see it."),
        input,
      ),
      footer: [copy],
    });
  }
}
