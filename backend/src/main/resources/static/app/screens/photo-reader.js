/* =============================================================================
   Read a photo or a scan, on this device (P-13).

   The photo is read by ocr.js in this browser. The words it found are sent
   with the photo to the same endpoint a PDF goes to, which stores the photo
   as proof and picks out what it can — so a photographed policy bond and a
   downloaded PDF of one end up in the same form.

   Each field comes back as a chip with the patch of the photo it was read
   from beside it, so "Policy number · 5567123456" can be checked against the
   paper at a glance. Nothing becomes a holding until the person goes on to the
   form and saves it.
   ============================================================================= */

import { api } from "../api.js";
import { el, mount, sheet, withBusy, notice, toast } from "../ui.js";
import { state, findType } from "../state.js";
import { t } from "../i18n.js";
import { readPhoto, releaseReader, crop } from "../ocr.js";
import { locate } from "../ocr-layout.js";

/**
 * onFound(result) is called with the parse-document result when the person
 * chooses to go on; capture.js carries it into the form, as it does for a PDF.
 */
export function openPhotoReader(file, { onFound, onFallback } = {}) {
  const preview = el("img.photo-preview", { alt: t("ocr.previewAlt") });
  const url = URL.createObjectURL(file);
  preview.src = url;

  const meter = el("div.meter", { role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": "0",
    "aria-label": t("ocr.reading") }, el("div.meter-fill", { style: { width: "0%" } }));
  const status = el("p.caption", { "aria-live": "polite", style: { margin: 0 } }, t("ocr.preparing"));
  const found = el("div.stack-3", {});
  const next = el("button.btn.btn-primary.grow", { type: "button", disabled: true }, t("ocr.checkDetails"));
  let result = null;

  const progress = (message) => {
    const share = message.status === "recognizing text" ? 0.25 + 0.75 * (message.progress || 0)
      : 0.25 * (message.progress || 0);
    const percent = Math.round(Math.max(0, Math.min(1, share)) * 100);
    meter.firstChild.style.width = `${percent}%`;
    meter.setAttribute("aria-valuenow", String(percent));
    status.textContent = message.status === "recognizing text" ? t("ocr.reading") : t("ocr.preparing");
  };

  const modal = sheet({
    title: t("ocr.title"),
    onClose: () => { URL.revokeObjectURL(url); releaseReader(); },
    body: el("div.stack-3", {},
      notice(t("ocr.private")),
      el("div.photo-frame", {}, preview),
      meter,
      status,
      found,
    ),
    footer: [next],
  });

  next.onclick = () => {
    if (!result) return;
    modal.close();
    onFound?.(result);
  };

  (async () => {
    let reading;
    try {
      reading = await readPhoto(file, { onProgress: progress });
    } catch {
      mount(found, notice(t("ocr.error.failed"), { tone: "alert", role: "alert" }));
      status.textContent = "";
      return;
    }
    meter.hidden = true;

    if (!reading.text.trim()) {
      status.textContent = "";
      mount(found, notice(t("ocr.error.nothing"), { tone: "alert", role: "alert" }),
        el("button.btn", { type: "button", onclick: () => { modal.close(); onFallback?.(); } }, t("ocr.fillIn")));
      return;
    }

    status.textContent = t("ocr.sending");
    await withBusy(next, async () => {
      try {
        result = await api.parseDocument(state.household.id, file, reading.text);
      } catch (error) {
        mount(found, notice(error.message, { tone: "alert", role: "alert" }));
        return;
      }
    });
    if (!result) return;

    status.textContent = reading.confidence < 60 ? t("ocr.lowConfidence") : "";
    const fields = result.fields || [];
    mount(found,
      fields.length === 0
        ? notice(result.note || t("ocr.error.nothingUseful"))
        : el("div.stack-2", {},
            el("span.overline", {}, t("ocr.found", { count: fields.length })),
            el("div.ocr-chips", {}, ...fields.map((field) => chip(field, reading))),
            result.note && el("p.caption", { style: { margin: 0 } }, result.note),
          ),
    );
    const type = fields.find((field) => field.key === "typeId");
    next.disabled = false;
    if (!type || !findType(type.value)) next.textContent = t("ocr.pickType");
    toast(t("ocr.kept"));
  })();
}

/** A field, with the patch of the photo it came from when that can be found. */
function chip(field, reading) {
  const box = locate(reading, field.sourceText) || locate(reading, field.display);
  const image = box && crop(reading.canvas, box);
  return el("div.ocr-chip", {},
    image
      ? el("img.ocr-crop", { src: image, alt: t("ocr.cropAlt", { text: field.sourceText || field.display }) })
      : el("span.caption", {}, t("ocr.noCrop")),
    el("div", {},
      el("div.caption", {}, field.label),
      el("div.ocr-value", {}, field.display),
    ),
  );
}
