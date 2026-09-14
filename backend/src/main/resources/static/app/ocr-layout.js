/* =============================================================================
   Words read from a picture, and where each one was (P-13).

   No DOM and no OCR engine: tesseract.js's blocks come in, and out comes one
   piece of text with every word's position in it, so a field the server finds
   in that text ("Policy No: 5567123456") can be traced back to the patch of the
   photo it was read from. scripts/check-ocr-layout.js tests it without a
   browser.
   ============================================================================= */

/** Below this, a word is more likely a smudge than a word. */
export const MIN_WORD_CONFIDENCE = 30;

/**
 * blocks → { text, words: [{ text, start, end, bbox, confidence }] }.
 *
 * Words on a line are joined with a space and lines with a newline, in the
 * engine's reading order. `start`/`end` index into `text`.
 */
export function layout(blocks) {
  let text = "";
  const words = [];
  for (const block of blocks || []) {
    for (const paragraph of block.paragraphs || []) {
      for (const line of paragraph.lines || []) {
        const kept = (line.words || []).filter((word) =>
          String(word.text || "").trim() !== "" && (Number(word.confidence) || 0) >= MIN_WORD_CONFIDENCE);
        if (!kept.length) continue;
        if (text) text += "\n";
        kept.forEach((word, index) => {
          if (index) text += " ";
          const value = String(word.text).trim();
          words.push({
            text: value,
            start: text.length,
            end: text.length + value.length,
            bbox: word.bbox,
            confidence: Math.round(Number(word.confidence) || 0),
          });
          text += value;
        });
      }
    }
  }
  return { text, words };
}

/**
 * Where a phrase was, as the box around the words it covers, or null.
 *
 * The server condenses whitespace before parsing, so the phrase is found the
 * same way: case and runs of space are ignored.
 */
export function locate(reading, phrase) {
  const wanted = String(phrase || "").trim().replace(/\s+/g, " ").toLowerCase();
  if (!wanted || !reading?.words?.length) return null;

  // Search a whitespace-collapsed copy, remembering where each of its
  // characters came from in the original.
  const map = [];
  let flat = "";
  for (let i = 0; i < reading.text.length; i += 1) {
    const ch = reading.text[i];
    if (/\s/.test(ch)) {
      if (flat.endsWith(" ") || flat === "") continue;
      flat += " ";
    } else {
      flat += ch.toLowerCase();
    }
    map.push(i);
  }
  const at = flat.indexOf(wanted);
  if (at < 0) return null;
  const start = map[at];
  const end = map[at + wanted.length - 1] + 1;

  const covered = reading.words.filter((word) => word.start < end && word.end > start && word.bbox);
  if (!covered.length) return null;
  return {
    x0: Math.min(...covered.map((w) => w.bbox.x0)),
    y0: Math.min(...covered.map((w) => w.bbox.y0)),
    x1: Math.max(...covered.map((w) => w.bbox.x1)),
    y1: Math.max(...covered.map((w) => w.bbox.y1)),
  };
}

/**
 * The size to read a photo at: long side no more than `max` pixels, never
 * enlarged. A 12-megapixel photo takes many times longer to read and reads no
 * better than one at print resolution for a page.
 */
export function fitWithin(width, height, max = 2200) {
  const scale = Math.min(1, max / Math.max(width || 1, height || 1));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), scale };
}
