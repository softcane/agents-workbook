package io.github.softcane.workbook.proxy.provider;

/**
 * The provider ended the hidden turn with a refusal, so it opened the workbook call and wrote nothing
 * into it. Distinct from a decode failure on purpose: the note is not truncated, damaged, or late, and
 * reporting it as any of those sends the reader hunting a parser bug that does not exist. The wording
 * of the tool is what the model declined, so only a different wording changes the outcome.
 */
public final class CaptureRefusedException extends RuntimeException {
    public CaptureRefusedException(String message) {
        super(message);
    }
}
