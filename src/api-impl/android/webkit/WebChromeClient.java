package android.webkit;

import android.app.Activity;
import android.net.Uri;
import android.content.Intent;

public class WebChromeClient {
	public void onPermissionRequest(PermissionRequest request) {
		if (request != null)
			request.deny();
	}

	public void onPermissionRequestCanceled(PermissionRequest request) {}

	public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
					 FileChooserParams fileChooserParams) {
		return false;
	}

	public static abstract class FileChooserParams {
		public static final int MODE_OPEN = 0;
		public static final int MODE_OPEN_MULTIPLE = 1;
		public static final int MODE_SAVE = 3;

		public abstract int getMode();
		public abstract String[] getAcceptTypes();
		public abstract boolean isCaptureEnabled();
		public abstract CharSequence getTitle();
		public abstract String getFilenameHint();
		public abstract Intent createIntent();

		public static Uri[] parseResult(int resultCode, Intent data) {
			if (resultCode != Activity.RESULT_OK || data == null)
				return null;
			Uri uri = data.getData();
			if (uri != null)
				return new Uri[] { uri };
			return null;
		}
	}
}
