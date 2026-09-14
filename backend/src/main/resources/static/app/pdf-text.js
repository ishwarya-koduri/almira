/* =============================================================================
   Opening a password-protected PDF on this device (P-11).

   A consolidated account statement arrives as a PDF locked with a password the
   registrar chose — usually built from the investor's PAN and date of birth.
   The server refuses protected PDFs outright (capture/TextExtractor.kt), and
   that is right: sending the password to a server so it can read the file is
   exactly the thing a family should not have to trust anyone with.

   So the file is opened here, by pdf.js running in this browser, served by this
   app from app/vendor (a pinned copy; app/vendor/SOURCE says which and where
   from). The file and the password stay in memory on this page: nothing here
   calls the network, and nothing is stored. What leaves the device later is
   only the rows the person chose, through the ordinary import.
   ============================================================================= */

const PDFJS = "/app/vendor/pdfjs-6.3.289/pdf.min.mjs";
const WORKER = "/app/vendor/pdfjs-6.3.289/pdf.worker.min.mjs";

/** A statement runs to a handful of pages; a 200-page PDF is not one. */
export const MAX_BYTES = 15 * 1024 * 1024;
export const MAX_PAGES = 60;

export class PdfError extends Error {
  /** code: "password-needed" | "password-wrong" | "too-large" | "unreadable" */
  constructor(code) {
    super(code);
    this.code = code;
  }
}

let loading = null;
function library() {
  loading ||= import(PDFJS).then((pdfjs) => {
    pdfjs.GlobalWorkerOptions.workerSrc = WORKER;
    return pdfjs;
  }).catch((error) => { loading = null; throw error; });
  return loading;
}

/**
 * The document's text, as pdf.js items per page.
 *
 * Rejects with a PdfError whose code says what to ask the person: a password,
 * a different password, or a different file.
 */
export async function readPdf(file, password) {
  if (file.size > MAX_BYTES) throw new PdfError("too-large");
  const pdfjs = await library();
  // pdf.js transfers the buffer to its worker, so each attempt gets its own copy.
  const data = new Uint8Array(await file.arrayBuffer());

  const task = pdfjs.getDocument({
    data,
    password: password || undefined,
    // Nothing fetched on the document's behalf: no fonts, maps or image
    // decoders from anywhere, and no script inside the PDF ever runs.
    isEvalSupported: false,
    enableXfa: false,
    disableFontFace: true,
    useSystemFonts: false,
    stopAtErrors: false,
    verbosity: 0,
  });

  let document;
  try {
    document = await task.promise;
  } catch (error) {
    await task.destroy().catch(() => undefined);
    if (error?.name === "PasswordException") {
      throw new PdfError(error.code === pdfjs.PasswordResponses.INCORRECT_PASSWORD ? "password-wrong" : "password-needed");
    }
    throw new PdfError("unreadable");
  }

  try {
    if (document.numPages > MAX_PAGES) throw new PdfError("too-large");
    const pages = [];
    for (let number = 1; number <= document.numPages; number += 1) {
      const page = await document.getPage(number);
      const content = await page.getTextContent();
      pages.push(content.items);
      page.cleanup();
    }
    return pages;
  } catch (error) {
    throw error instanceof PdfError ? error : new PdfError("unreadable");
  } finally {
    await task.destroy().catch(() => undefined);
  }
}
