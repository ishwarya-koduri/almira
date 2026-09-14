/* =============================================================================
   Words from a photo, and where each was, without a browser (P-13).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         -m scripts/check-ocr-layout.js

   The engine itself is checked in a browser (scripts/browser-checks); this is
   the part in between, which decides what text the server is sent and which
   patch of the photo each field is shown with.
   ============================================================================= */

import { layout, locate, fitWithin, MIN_WORD_CONFIDENCE } from "../backend/src/main/resources/static/app/ocr-layout.js";

const log = typeof print === "function" ? print : console.log;
let failures = 0;
function expect(label, actual, wanted) {
  const a = JSON.stringify(actual);
  const w = JSON.stringify(wanted);
  if (a === w) log(`  ok   ${label}`);
  else { failures += 1; log(`  FAIL ${label}\n         got    ${a}\n         wanted ${w}`); }
}

const word = (text, x0, y0, confidence = 90) => ({ text, confidence, bbox: { x0, y0, x1: x0 + text.length * 10, y1: y0 + 20 } });
const blocks = [{
  paragraphs: [{
    lines: [
      { words: [word("LIFE", 10, 10), word("INSURANCE", 60, 10)] },
      { words: [word("Policy", 10, 50), word("No:", 80, 50), word("5567123456", 120, 50)] },
      { words: [word("~", 10, 90, 12)] },
      { words: [word("Nominee:", 10, 130), word("Aarav", 100, 130), word("%%", 160, 130, 8)] },
    ],
  }],
}];

log("\nLayout");
const reading = layout(blocks);
expect("words join with spaces, lines with newlines",
  reading.text, "LIFE INSURANCE\nPolicy No: 5567123456\nNominee: Aarav");
expect("a line of smudges is dropped, not left as an empty line", reading.text.includes("\n\n"), false);
expect(`words under ${MIN_WORD_CONFIDENCE}% confidence are not sent`, reading.words.some((w) => w.text === "%%"), false);
expect("each word knows where it is in the text",
  reading.words.map((w) => reading.text.slice(w.start, w.end)), reading.words.map((w) => w.text));
expect("nothing read is empty text", layout([]), { text: "", words: [] });

log("\nWhere a field came from");
expect("a phrase across words is the box around them",
  locate(reading, "Policy No: 5567123456"), { x0: 10, y0: 50, x1: 220, y1: 70 });
expect("case and spacing as the server condenses them",
  locate(reading, "policy   no:\n5567123456"), { x0: 10, y0: 50, x1: 220, y1: 70 });
expect("a phrase across a line break", locate(reading, "INSURANCE Policy"), { x0: 10, y0: 10, x1: 150, y1: 70 });
expect("a single word", locate(reading, "Aarav"), { x0: 100, y0: 130, x1: 150, y1: 150 });
expect("what is not there has no box", locate(reading, "Sum assured"), null);
expect("no phrase has no box", locate(reading, ""), null);

log("\nSize to read at");
expect("a 12 MP photo is scaled down to 2200 on its long side", fitWithin(4000, 3000), { width: 2200, height: 1650, scale: 0.55 });
expect("a small scan is never enlarged", fitWithin(800, 600), { width: 800, height: 600, scale: 1 });
expect("portrait too", fitWithin(3000, 4400).height, 2200);

log(failures === 0 ? "\nAll OCR layout checks passed.\n" : `\n${failures} OCR layout checks failed.\n`);
if (typeof quit === "function") quit(failures === 0 ? 0 : 1);
else if (typeof process !== "undefined") process.exit(failures === 0 ? 0 : 1);
