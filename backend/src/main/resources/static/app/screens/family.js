/* Family — the roster, and what each person can see.
   Note what is deliberately absent: another member's private count or values.
   You see who is here and what they have shared with you, nothing more. */

import { api } from "../api.js";
import { el, mount, sheet, field, textInput, select, withBusy, toast, empty } from "../ui.js";
import { state } from "../state.js";
import { reload } from "../app.js";

export async function familyScreen(host) {
  const members = state.members;
  const canManage = ["owner", "admin"].includes(state.household.myRole);

  mount(host, el("div.stack", {},
    el("div.row-between.wrap", {},
      el("div", {},
        el("h1", {}, state.household.name),
        el("p.muted", { style: { margin: 0 } },
          `${members.length} ${members.length === 1 ? "person" : "people"}`),
      ),
      canManage && el("button.btn.btn-primary", { type: "button", onclick: () => addMember() }, "＋ Add someone"),
    ),

    el("div.card.card-tight", {},
      el("div.list", {}, ...members.map((member) => el("div.list-row", { style: { cursor: "default" } },
        el("div.grow", {},
          el("div.title", {}, member.displayName, member.isMe && el("span.pill", { style: { marginLeft: "8px" } }, "You")),
          el("div.meta", {}, [
            member.relationship,
            member.isMinor && "minor",
            member.isManaged ? "no login yet" : member.role,
          ].filter(Boolean).join(" · ")),
        ),
        canManage && member.isManaged && el("button.btn.btn-sm", {
          type: "button", onclick: () => invite(member),
        }, "Invite to sign in"),
      ))),
    ),

    el("div.banner.banner-accent", {},
      el("div", {},
        el("b", {}, "Everyone keeps their own privacy. "),
        "A role decides what someone can ", el("i", {}, "do"), " — invite people, edit shared entries. ",
        "It never decides what they can ", el("i", {}, "see"), ". ",
        "Private entries stay private, including from the household owner.",
      ),
    ),
  ));

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
    const nameField = field({ label: "Name", control: name, required: true });

    const save = el("button.btn.btn-primary", { type: "button" }, "Add");
    const modal = sheet({
      title: "Add someone",
      body: el("div.stack-3", {},
        nameField,
        field({ label: "Relationship to you", control: relationship }),
        field({ label: "Date of birth", control: dob,
          help: "Optional. Helps us flag accounts held for a minor." }),
      ),
      footer: [save],
    });

    save.onclick = () => withBusy(save, async () => {
      if (!name.value.trim()) { nameField.setError("Give this person a name"); return; }
      try {
        await api.addMember(state.household.id, {
          displayName: name.value.trim(),
          relationship: relationship.value,
          dateOfBirth: dob.value || null,
        });
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
