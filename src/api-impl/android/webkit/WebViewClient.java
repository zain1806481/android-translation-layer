package android.webkit;

import android.graphics.Bitmap;

public class WebViewClient {
	// Match Android: 2-arg forwards to 3-arg so app overrides of (view,url,favicon) run.
	public void onPageStarted(WebView view, String url) {
		onPageStarted(view, url, null);
	}

	public void onPageStarted(WebView view, String url, Bitmap favicon) {}

	public void onPageFinished(WebView view, String url) {}

	public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {}
}
