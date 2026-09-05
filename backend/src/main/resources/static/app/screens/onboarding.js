/* "Who are we tracking for?" — one decision, then straight to adding something.
   docs/03 §1 puts a first record within two minutes of install; nothing here
   should stand between someone and that. */

import { api } from "../api.js";
import { el, mount, field, textInput, withBusy } from "../ui.js";

export function onboardingScreen(onDone) {
  const host = el("main.narrow", {});
  let mode = "just_me";
  let visibility = "private";

  const nameInput = textInput({ placeholder: "The Koduri household", "aria-label": "Household name" });
  const displayInput = textInput({ placeholder: "Your name", "aria-label": "Your name" });

  const choice = (value, current, title, description, onPick) => el("button.choice", {
    type: "button", "aria-pressed": value === current, onclick: onPick,
  },
    el("div", {}, el("div.ct", {}, title), el("div.cd", {}, description)),
    el("span.radio", { "aria-hidden": "true" }),
  );

  const submit = el("button.btn.btn-primary", { type: "submit" }, "Create my household");

  function draw() {
    mount(host, el("div.stack", {},
      el("div", {},
        el("h1", {}, "Let's set things up"),
        el("p.muted", {}, "This takes about a minute. You can change any of it later."),
      ),

      el("div.card.stack-3", {},
        el("h4", {}, "Who are we tracking for?"),
        el("div.choices", {},
          choice("just_me", mode, "Just me", "Your own investments and loans.",
            () => { mode = "just_me"; draw(); }),
          choice("family", mode, "Me and my family",
            "Track for a spouse, children or parents — each person keeps their own privacy.",
            () => { mode = "family"; draw(); }),
        ),
      ),

      el("div.card.stack-3", {},
        el("h4", {}, "What should new entries default to?"),
        el("p.caption.muted", { style: { margin: 0 } },
          "Private means only you can see it — not even a household admin. " +
          "You can share any single entry whenever you want."),
        el("div.choices", {},
          choice("private", visibility, "Private by default",
            "Nothing is shared unless you choose to share it.",
            () => { visibility = "private"; draw(); }),
          choice("household", visibility, "Shared by default",
            "New entries are visible to everyone in the household.",
            () => { visibility = "household"; draw(); }),
        ),
      ),

      el("form.card.stack-3", {
        onsubmit: async (event) => {
          event.preventDefault();
          await withBusy(submit, async () => {
            await api.createHousehold({
              name: nameInput.value.trim() || null,
              mode,
              defaultVisibility: visibility,
              displayName: displayInput.value.trim() || null,
            });
            await onDone();
          });
        },
      },
        field({ label: "Household name", control: nameInput, help: "Optional — we'll call it “My household” otherwise." }),
        field({ label: "What should we call you?", control: displayInput, help: "Shown next to the things you own." }),
        el("div.row", {}, submit),
      ),
    ));
  }

  draw();
  return host;
}
