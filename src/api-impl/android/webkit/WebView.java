package android.webkit;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.ViewGroup;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class WebView extends ViewGroup {

	private WebViewClient webViewClient;
	private WebChromeClient webChromeClient;
	private WebSettings settings;

	public WebView(Context context) {
		this(context, null);
	}

	public WebView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		settings = new WebSettings(this);
	}

	public WebSettings getSettings() {
		return settings;
	}

	public void setDownloadListener(DownloadListener downloadListener) {}

	public void setScrollBarStyle(int scrollBarStyle) {}

	public void setWebViewClient(WebViewClient webViewClient) {
		this.webViewClient = webViewClient;
	}

	public void setWebChromeClient(WebChromeClient client) {
		this.webChromeClient = client;
	}

	// to be used by native code
	void internalLoadChanged(int loadState, String url) {
		if (loadState == /*WEBKIT_LOAD_STARTED*/ 0 && webViewClient != null) {
			webViewClient.onPageStarted(this, url, null);
		} else if (loadState == /*WEBKIT_LOAD_FINISHED*/ 3 && webViewClient != null) {
			webViewClient.onPageFinished(this, url);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	void internalJsResult(ValueCallback callback, String jsonResult) {
		if (callback != null)
			callback.onReceiveValue(jsonResult);
	}

	void internalPermissionRequest(long nativeRequest, String origin, String[] resources) {
		PermissionRequest request = new NativePermissionRequest(nativeRequest, origin, resources);
		if (webChromeClient != null)
			webChromeClient.onPermissionRequest(request);
		else
			request.deny();
	}

	/**
	 * @return true if the Java chrome client will complete the chooser (via ValueCallback)
	 */
	boolean internalShowFileChooser(long nativeRequest, boolean multiple, String[] acceptTypes) {
		ValueCallback<Uri[]> callback = new ValueCallback<Uri[]>() {
			private boolean done;
			@Override
			public void onReceiveValue(Uri[] uris) {
				if (done)
					return;
				done = true;
				if (uris == null || uris.length == 0) {
					native_fileChooserCancel(nativeRequest);
					return;
				}
				String[] paths = new String[uris.length];
				for (int i = 0; i < uris.length; i++)
					paths[i] = uris[i] != null ? uris[i].toString() : null;
				native_fileChooserSelect(nativeRequest, paths);
			}
		};
		WebChromeClient.FileChooserParams params = new AtlFileChooserParams(multiple, acceptTypes);
		boolean handled = webChromeClient != null && webChromeClient.onShowFileChooser(this, callback, params);
		if (!handled) {
			// Desktop fallback: GTK dialog via Activity path isn't available without a chrome client.
			native_fileChooserOpenGtk(nativeRequest, multiple, acceptTypes != null && acceptTypes.length > 0 ? acceptTypes[0] : null);
		}
		return handled;
	}

	public void setVerticalScrollBarEnabled(boolean enabled) {}
	public void setVerticalScrollbarOverlay(boolean overlay) {}
	public void setInitialScale(int scaleInPercent) {}

	public void addJavascriptInterface(Object object, String name) {
		// HACK: directly call onRenderingDone for OctoDroid, as the javascript interface is not implemented yet
		if (object.getClass().getName().startsWith("com.gh4a") && "NativeClient".equals(name)) {
			try {
				object.getClass().getMethod("onRenderingDone").invoke(object);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
		}
	}

	public void removeAllViews() {}

	public void destroy() {}

	public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
		if ("base64".equals(encoding)) {
			data = new String(Base64.decode(data, 0));
		}
		if (mimeType != null && mimeType.contains(";")) {
			mimeType = mimeType.substring(0, mimeType.indexOf(";"));
		}
		// webkit doesn't allow overwriting the file:// uri scheme. So we replace it with the android-asset:// scheme
		data = data.replace("file:///android_asset/", "android-asset:///assets/");
		native_loadDataWithBaseURL(widget, baseUrl, data, mimeType, encoding);
	}

	public void loadUrl(String url) {
		if (url != null && url.regionMatches(true, 0, "javascript:", 0, 11)) {
			String script = url.substring(11);
			try {
				script = URLDecoder.decode(script, StandardCharsets.UTF_8.name());
			} catch (Exception ignored) {}
			evaluateJavascript(script, null);
			return;
		}
		System.out.println("ATL WebView.loadUrl: " + url);
		native_loadUrl(widget, url);
	}

	public void stopLoading() {}

	public String getUrl() {
		return native_getUrl(widget);
	}

	public boolean canGoBack() {
		return native_canGoBack(widget);
	}

	public void goBack() {
		native_goBack(widget);
	}

	public WebBackForwardList saveState(Bundle outState) {
		return null;
	}

	public WebBackForwardList restoreState(Bundle inState) {
		return null;
	}

	// to be used by native code
	AssetManager internalGetAssetManager() {
		return getContext().getResources().getAssets();
	}

	public void onPause() {}

	public void onResume() {}

	public boolean isPaused() {
		return false;
	}

	public void resumeTimers() {}

	public void loadData(String data, String mimeType, String encoding) {
		loadDataWithBaseURL("about:blank", data, mimeType, encoding, "about:blank");
	}

	@SuppressWarnings("rawtypes")
	public void evaluateJavascript(String script, ValueCallback resultCallback) {
		if (script == null)
			return;
		native_evaluateJavascript(widget, script, resultCallback);
	}

	public static void setWebContentsDebuggingEnabled(boolean enabled) {}

	public void clearFormData() {}

	public void clearHistory() {}

	public void clearMatches() {}

	public void clearSslPreferences() {}

	public void clearCache(boolean includeDiskFiles) {}

	void applyJavaScriptEnabled(boolean enabled) {
		native_setJavaScriptEnabled(widget, enabled);
	}

	void applyDomStorageEnabled(boolean enabled) {
		native_setDomStorageEnabled(widget, enabled);
	}

	void applyMediaPlaybackRequiresUserGesture(boolean require) {
		native_setMediaPlaybackRequiresUserGesture(widget, require);
	}

	// directly accessed by androidx WebViewGlueCommunicator to get ClassLoader
	private static Object getFactory() {
		return new Object();
	}

	private static final class NativePermissionRequest extends PermissionRequest {
		private final long nativeRequest;
		private final String origin;
		private final String[] resources;
		private boolean finished;

		NativePermissionRequest(long nativeRequest, String origin, String[] resources) {
			this.nativeRequest = nativeRequest;
			this.origin = origin;
			this.resources = resources != null ? resources : new String[0];
		}

		@Override
		public Uri getOrigin() {
			return origin != null ? Uri.parse(origin) : Uri.parse("null");
		}

		@Override
		public String[] getResources() {
			return resources;
		}

		@Override
		public void grant(String[] allowed) {
			if (finished)
				return;
			finished = true;
			native_permissionAllow(nativeRequest);
		}

		@Override
		public void deny() {
			if (finished)
				return;
			finished = true;
			native_permissionDeny(nativeRequest);
		}
	}

	private static final class AtlFileChooserParams extends WebChromeClient.FileChooserParams {
		private final boolean multiple;
		private final String[] acceptTypes;

		AtlFileChooserParams(boolean multiple, String[] acceptTypes) {
			this.multiple = multiple;
			this.acceptTypes = acceptTypes != null ? acceptTypes : new String[0];
		}

		@Override
		public int getMode() {
			return multiple ? MODE_OPEN_MULTIPLE : MODE_OPEN;
		}

		@Override
		public String[] getAcceptTypes() {
			return acceptTypes;
		}

		@Override
		public boolean isCaptureEnabled() {
			return false;
		}

		@Override
		public CharSequence getTitle() {
			return null;
		}

		@Override
		public String getFilenameHint() {
			return null;
		}

		@Override
		public Intent createIntent() {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			if (acceptTypes.length == 1 && acceptTypes[0] != null && !acceptTypes[0].isEmpty())
				intent.setType(acceptTypes[0]);
			else
				intent.setType("*/*");
			if (multiple)
				intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
			return intent;
		}
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	private native void native_loadDataWithBaseURL(long widget, String baseUrl, String data, String mimeType, String encoding);
	private native void native_loadUrl(long widget, String url);
	private native String native_getUrl(long widget);
	private native boolean native_canGoBack(long widget);
	private native void native_goBack(long widget);
	private native void native_setJavaScriptEnabled(long widget, boolean enabled);
	private native void native_setDomStorageEnabled(long widget, boolean enabled);
	private native void native_setMediaPlaybackRequiresUserGesture(long widget, boolean require);
	@SuppressWarnings("rawtypes")
	private native void native_evaluateJavascript(long widget, String script, ValueCallback callback);
	private static native void native_permissionAllow(long nativeRequest);
	private static native void native_permissionDeny(long nativeRequest);
	private static native void native_fileChooserSelect(long nativeRequest, String[] uris);
	private static native void native_fileChooserCancel(long nativeRequest);
	private static native void native_fileChooserOpenGtk(long nativeRequest, boolean multiple, String mimeType);
}
