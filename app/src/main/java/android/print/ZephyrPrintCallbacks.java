package android.print;

/**
 * v1.7.67: a bridge into android.print.
 *
 * {@link PrintDocumentAdapter.LayoutResultCallback} and its write counterpart
 * have package-private constructors, so they cannot be subclassed from
 * application code — the framework expects only the print spooler to create
 * them. Driving an adapter straight into a file (rather than through the
 * printer-picker dialog) needs exactly that, and declaring this one class in
 * the framework's own package is the long-standing way to get it.
 *
 * Nothing framework-internal is touched beyond the constructors: each callback
 * simply forwards to a plain listener the Kotlin side can implement.
 */
public final class ZephyrPrintCallbacks {

    private ZephyrPrintCallbacks() {}

    public interface OnLayout {
        void finished(PrintDocumentInfo info);
        void failed(CharSequence error);
    }

    public interface OnWrite {
        void finished();
        void failed(CharSequence error);
    }

    public static PrintDocumentAdapter.LayoutResultCallback layout(final OnLayout cb) {
        return new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                cb.finished(info);
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                cb.failed(error);
            }

            @Override
            public void onLayoutCancelled() {
                cb.failed("layout cancelled");
            }
        };
    }

    public static PrintDocumentAdapter.WriteResultCallback write(final OnWrite cb) {
        return new PrintDocumentAdapter.WriteResultCallback() {
            @Override
            public void onWriteFinished(PageRange[] pages) {
                cb.finished();
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                cb.failed(error);
            }

            @Override
            public void onWriteCancelled() {
                cb.failed("write cancelled");
            }
        };
    }
}
