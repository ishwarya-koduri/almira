/* =============================================================================
   Reading a photo or a scan on this device (P-13).

   The server cannot read a picture (capture/TextExtractor.kt), and a hosted OCR
   service would mean sending a photo of someone's policy bond to a third party.
   So the words are read here, by tesseract.js running in this browser, with the
   engine, its WebAssembly core and the English model all served by this app
   from app/vendor (pinned; app/vendor/SOURCE). Every path below is given
   explicitly: left to its defaults, tesseract.js fetches all three from a CDN,
   and ClientVendorAssetsTest fails if any of them goes missing from here.

   The photo is scaled and handed to a worker in memory, and nothing about it is
   stored: the words read go no further than this page until the person sends
   them. The engine and model are ordinary static files the service worker keeps.
   ============================================================================= */

import { layout, fitWithin } from "./ocr-layout.js";

const BASE = "/app/vendor/tesseract-7.0.0";

/**
 * Whether this browser can read a photo at all: WebAssembly with SIMD (Chrome
 * 91, Firefox 89, Safari 16.4) and workers. Only one core is shipped, so an
 * older browser is told plainly instead of being sent a second 3 MB file.
 */
export function canReadPhotos() {
  try {
    if (typeof Worker !== "function" || typeof WebAssembly !== "object") return false;
    // The smallest module that uses a SIMD instruction; validates only where SIMD exists.
    return WebAssembly.validate(new Uint8Array([
      0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11,
    ]));
  } catch {
    return false;
  }
}

let engine = null;

async function worker(onProgress) {
  if (engine) return engine;
  const { default: Tesseract } = await import(`${BASE}/tesseract.esm.min.js`);
  engine = Tesseract.createWorker("eng", Tesseract.OEM.LSTM_ONLY, {
    workerPath: `${BASE}/worker.min.js`,
    corePath: `${BASE}/tesseract-core-simd-lstm.js`,
    langPath: `${BASE}/lang`,
    gzip: true,
    // A worker from a blob: URL cannot be kept by the service worker and hides
    // where the script came from; the file is on this origin already.
    workerBlobURL: false,
    // No copy of the model in IndexedDB: the service worker caches it as a
    // static file, and there is then one place it lives, not two.
    cacheMethod: "none",
    logger: (message) => onProgress?.(message),
  }).catch((error) => { engine = null; throw error; });
  return engine;
}

/** A photo, scaled to a readable size, as a canvas the engine can take. */
async function prepare(file) {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  const size = fitWithin(bitmap.width, bitmap.height);
  const canvas = document.createElement("canvas");
  canvas.width = size.width;
  canvas.height = size.height;
  const context = canvas.getContext("2d");
  context.drawImage(bitmap, 0, 0, size.width, size.height);
  bitmap.close?.();
  return canvas;
}

/**
 * The words in a photo, with where each was.
 *
 * Resolves to { text, words, confidence, canvas } — the canvas is the scaled
 * photo the positions refer to, for cutting out the patch a field came from.
 * onProgress receives tesseract.js's { status, progress } messages.
 */
export async function readPhoto(file, { onProgress } = {}) {
  const canvas = await prepare(file);
  const reader = await worker(onProgress);
  const { data } = await reader.recognize(canvas, {}, { text: true, blocks: true });
  const reading = layout(data.blocks);
  return { ...reading, confidence: Math.round(Number(data.confidence) || 0), canvas };
}

/** Frees the engine and its model — a phone should not hold them for a sheet it has left. */
export async function releaseReader() {
  const current = engine;
  engine = null;
  if (!current) return;
  try { await (await current).terminate(); } catch { /* it never started, or already stopped */ }
}

/** The patch of the photo a box covers, with a little room around it, as a data URL. */
export function crop(canvas, box, { padding = 6, maxWidth = 240 } = {}) {
  if (!box) return null;
  const x = Math.max(0, box.x0 - padding);
  const y = Math.max(0, box.y0 - padding);
  const width = Math.min(canvas.width - x, box.x1 - box.x0 + padding * 2);
  const height = Math.min(canvas.height - y, box.y1 - box.y0 + padding * 2);
  if (width <= 0 || height <= 0) return null;
  const scale = Math.min(1, maxWidth / width);
  const out = document.createElement("canvas");
  out.width = Math.round(width * scale);
  out.height = Math.round(height * scale);
  out.getContext("2d").drawImage(canvas, x, y, width, height, 0, 0, out.width, out.height);
  return out.toDataURL("image/png");
}
