#include <gio/gunixinputstream.h>
#include <gtk/gtk.h>
#include <jsc/jsc.h>
#include <webkit/webkit.h>

#include <androidfw/androidfw_c_api.h>

#include "../defines.h"
#include "../util.h"

#include "../AssetInputStream.h"
#include "WrapperWidget.h"

#include "../generated_headers/android_view_View.h"
#include "../generated_headers/android_webkit_WebView.h"

extern GtkWindow *window;

static void asset_uri_scheme_request_cb(WebKitURISchemeRequest *request, gpointer user_data)
{
	const gchar *path = webkit_uri_scheme_request_get_path(request);
	if (path == NULL)
		return;
	path += 1; // remove the leading '/'
	JNIEnv *env = get_jni_env();
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(webkit_uri_scheme_request_get_web_view(request))));
	jobject asset_manager_obj = (*env)->CallObjectMethod(env, wrapper->jobj, handle_cache.webview.internalGetAssetManager);
	struct AssetManager *asset_manager = _PTR(_GET_LONG_FIELD(asset_manager_obj, "mObject"));
	struct Asset *asset = AssetManager_openNonAsset(asset_manager, path, ACCESS_STREAMING);
	GInputStream *stream = asset_input_stream_new(asset);
	webkit_uri_scheme_request_finish(request, stream, Asset_getLength(asset), NULL);
	g_object_unref(stream);
}

static void web_view_load_changed(WebKitWebView *web_view, WebKitLoadEvent load_event, gpointer user_data)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(web_view)));
	JNIEnv *env = get_jni_env();
	const gchar *uri = webkit_web_view_get_uri(web_view);
	g_message("ATL WebView load-changed event=%d uri=%s", (int)load_event, uri ? uri : "(null)");
	(*env)->CallVoidMethod(env, wrapper->jobj, handle_cache.webview.internalLoadChanged, load_event, _JSTRING(uri));
}

static gboolean web_view_load_failed(WebKitWebView *web_view, WebKitLoadEvent load_event,
				     gchar *failing_uri, GError *error, gpointer user_data)
{
	g_warning("ATL WebView load-failed event=%d uri=%s err=%s",
		  (int)load_event,
		  failing_uri ? failing_uri : "(null)",
		  (error && error->message) ? error->message : "(null)");
	return FALSE;
}

static gboolean web_view_load_failed_tls(WebKitWebView *web_view, gchar *failing_uri,
					 GTlsCertificate *certificate, GTlsCertificateFlags errors,
					 gpointer user_data)
{
	g_warning("ATL WebView load-failed-tls uri=%s flags=0x%x",
		  failing_uri ? failing_uri : "(null)", (unsigned)errors);
	/* Allow self-signed / local Cockpit certs */
	return TRUE;
}

struct option_menu_data {
	WebKitOptionMenu *menu;
	GtkWidget *popover;
};

static void option_menu_row_activated(GtkListBox *box, GtkListBoxRow *row, gpointer user_data)
{
	struct option_menu_data *data = user_data;
	guint index = GPOINTER_TO_UINT(g_object_get_data(G_OBJECT(row), "atl-option-index"));
	webkit_option_menu_activate_item(data->menu, index);
	gtk_popover_popdown(GTK_POPOVER(data->popover));
}

static void option_menu_closed(GtkPopover *popover, gpointer user_data)
{
	struct option_menu_data *data = user_data;
	webkit_option_menu_close(data->menu);
	g_object_unref(data->menu);
	gtk_widget_unparent(GTK_WIDGET(popover));
	free(data);
}

static gboolean web_view_show_option_menu(WebKitWebView *web_view, WebKitOptionMenu *menu,
					  GdkRectangle *rectangle, gpointer user_data)
{
	(void)user_data;
	GtkWidget *popover = gtk_popover_new();
	gtk_widget_set_parent(popover, GTK_WIDGET(web_view));
	gtk_popover_set_has_arrow(GTK_POPOVER(popover), FALSE);
	gtk_popover_set_autohide(GTK_POPOVER(popover), TRUE);
	if (rectangle)
		gtk_popover_set_pointing_to(GTK_POPOVER(popover), rectangle);

	GtkWidget *scroller = gtk_scrolled_window_new();
	gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroller), GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
	gtk_scrolled_window_set_max_content_height(GTK_SCROLLED_WINDOW(scroller), 320);
	gtk_scrolled_window_set_propagate_natural_height(GTK_SCROLLED_WINDOW(scroller), TRUE);

	GtkWidget *list = gtk_list_box_new();
	gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_SINGLE);
	gtk_widget_add_css_class(list, "boxed-list");

	guint n = webkit_option_menu_get_n_items(menu);
	for (guint i = 0; i < n; i++) {
		WebKitOptionMenuItem *item = webkit_option_menu_get_item(menu, i);
		if (!item)
			continue;
		const gchar *label = webkit_option_menu_item_get_label(item);
		GtkWidget *row = gtk_list_box_row_new();
		GtkWidget *lbl = gtk_label_new(label ? label : "");
		gtk_label_set_xalign(GTK_LABEL(lbl), 0.0);
		gtk_widget_set_margin_start(lbl, 10);
		gtk_widget_set_margin_end(lbl, 10);
		gtk_widget_set_margin_top(lbl, 6);
		gtk_widget_set_margin_bottom(lbl, 6);
		if (webkit_option_menu_item_is_group_label(item)) {
			gtk_widget_add_css_class(lbl, "heading");
			gtk_list_box_row_set_selectable(GTK_LIST_BOX_ROW(row), FALSE);
			gtk_list_box_row_set_activatable(GTK_LIST_BOX_ROW(row), FALSE);
		} else {
			gtk_widget_set_sensitive(row, webkit_option_menu_item_is_enabled(item));
			g_object_set_data(G_OBJECT(row), "atl-option-index", GUINT_TO_POINTER(i));
			if (webkit_option_menu_item_is_selected(item))
				gtk_list_box_select_row(GTK_LIST_BOX(list), GTK_LIST_BOX_ROW(row));
		}
		gtk_list_box_row_set_child(GTK_LIST_BOX_ROW(row), lbl);
		gtk_list_box_append(GTK_LIST_BOX(list), row);
	}

	struct option_menu_data *data = calloc(1, sizeof(*data));
	data->menu = g_object_ref(menu);
	data->popover = popover;
	g_signal_connect(list, "row-activated", G_CALLBACK(option_menu_row_activated), data);
	g_signal_connect(popover, "closed", G_CALLBACK(option_menu_closed), data);

	gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(scroller), list);
	gtk_popover_set_child(GTK_POPOVER(popover), scroller);
	gtk_popover_popup(GTK_POPOVER(popover));
	g_message("ATL WebView show-option-menu items=%u", n);
	return TRUE;
}

static GtkWidget *web_view_create(WebKitWebView *web_view, WebKitNavigationAction *navigation_action,
				  gpointer user_data)
{
	(void)user_data;
	/* ATL is a single-window shell — fold window.open / target=_blank into the same WebView */
	WebKitURIRequest *req = webkit_navigation_action_get_request(navigation_action);
	const gchar *uri = req ? webkit_uri_request_get_uri(req) : NULL;
	g_message("ATL WebView create → same-view uri=%s", uri ? uri : "(null)");
	if (uri && *uri)
		webkit_web_view_load_uri(web_view, uri);
	return NULL;
}

static gboolean web_view_reload_after_crash(gpointer data)
{
	WebKitWebView *web_view = WEBKIT_WEB_VIEW(data);
	const gchar *uri = webkit_web_view_get_uri(web_view);
	if (uri && *uri)
		webkit_web_view_load_uri(web_view, uri);
	else
		webkit_web_view_reload(web_view);
	g_object_unref(web_view);
	return G_SOURCE_REMOVE;
}

static void web_view_process_terminated(WebKitWebView *web_view,
					WebKitWebProcessTerminationReason reason,
					gpointer user_data)
{
	(void)user_data;
	g_warning("ATL WebView web-process-terminated reason=%d — scheduling reload", (int)reason);
	/* Defer so WebKit can tear down the dead process before we spawn a new one */
	g_timeout_add(250, web_view_reload_after_crash, g_object_ref(web_view));
}

static gboolean web_view_permission_request(WebKitWebView *web_view, WebKitPermissionRequest *request, gpointer user_data)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(web_view)));
	JNIEnv *env = get_jni_env();
	const gchar *uri = webkit_web_view_get_uri(web_view);

	/* Device-info / non-media prompts: allow so getUserMedia enumeration works */
	if (WEBKIT_IS_DEVICE_INFO_PERMISSION_REQUEST(request)) {
		webkit_permission_request_allow(request);
		return TRUE;
	}

	g_auto(GStrv) resources = NULL;
	guint n = 0;
	if (WEBKIT_IS_USER_MEDIA_PERMISSION_REQUEST(request)) {
		WebKitUserMediaPermissionRequest *um = WEBKIT_USER_MEDIA_PERMISSION_REQUEST(request);
		gboolean audio = webkit_user_media_permission_is_for_audio_device(um);
		gboolean video = webkit_user_media_permission_is_for_video_device(um);
		resources = g_new0(gchar *, 3);
		if (audio)
			resources[n++] = g_strdup("android.webkit.resource.AUDIO_CAPTURE");
		if (video)
			resources[n++] = g_strdup("android.webkit.resource.VIDEO_CAPTURE");
	} else {
		/* Other permission types: still surface to Java as empty resources (grant = allow) */
		resources = g_new0(gchar *, 1);
	}

	jobjectArray jresources = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
	for (guint i = 0; i < n; i++)
		(*env)->SetObjectArrayElement(env, jresources, i, _JSTRING(resources[i]));

	g_object_ref(request);
	(*env)->CallVoidMethod(env, wrapper->jobj, handle_cache.webview.internalPermissionRequest,
			       _INTPTR(request), _JSTRING(uri ? uri : ""), jresources);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
		webkit_permission_request_deny(request);
		g_object_unref(request);
	}
	return TRUE;
}

static gboolean web_view_run_file_chooser(WebKitWebView *web_view, WebKitFileChooserRequest *request, gpointer user_data)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(web_view)));
	JNIEnv *env = get_jni_env();
	gboolean multiple = webkit_file_chooser_request_get_select_multiple(request);
	const gchar *const *mimes = webkit_file_chooser_request_get_mime_types(request);
	guint n = 0;
	if (mimes) {
		for (const gchar *const *p = mimes; *p; p++)
			n++;
	}
	jobjectArray jtypes = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
	for (guint i = 0; i < n; i++)
		(*env)->SetObjectArrayElement(env, jtypes, i, _JSTRING(mimes[i]));

	g_object_ref(request);
	jboolean handled = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.webview.internalShowFileChooser,
						     _INTPTR(request), multiple ? JNI_TRUE : JNI_FALSE, jtypes);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
		webkit_file_chooser_request_cancel(request);
		g_object_unref(request);
		return TRUE;
	}
	if (!handled) {
		/* GTK fallback already started from Java via native_fileChooserOpenGtk, or will cancel */
	}
	return TRUE;
}

struct eval_js_data {
	jobject webview;  /* global ref to android.webkit.WebView */
	jobject callback; /* global ref or NULL */
};

static void evaluate_javascript_finished(GObject *source, GAsyncResult *result, gpointer user_data)
{
	struct eval_js_data *data = user_data;
	GError *error = NULL;
	JSCValue *value = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source), result, &error);
	JNIEnv *env = get_jni_env();
	gchar *json = NULL;
	if (error) {
		g_warning("ATL WebView evaluateJavascript failed: %s", error->message);
		g_error_free(error);
	} else if (value) {
		json = jsc_value_to_json(value, 0);
		g_object_unref(value);
	}
	if (data->callback) {
		(*env)->CallVoidMethod(env, data->webview, handle_cache.webview.internalJsResult,
				       data->callback, json ? _JSTRING(json) : NULL);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
		_UNREF(data->callback);
	}
	_UNREF(data->webview);
	g_free(json);
	free(data);
}

struct gtk_file_chooser_data {
	WebKitFileChooserRequest *request;
};

static void gtk_file_chooser_open_finished(GObject *source, GAsyncResult *res, gpointer user_data)
{
	struct gtk_file_chooser_data *data = user_data;
	GError *error = NULL;
	GFile *file = gtk_file_dialog_open_finish(GTK_FILE_DIALOG(source), res, &error);
	if (file) {
		gchar *path = g_file_get_path(file);
		const gchar *files[] = { path, NULL };
		webkit_file_chooser_request_select_files(data->request, files);
		g_free(path);
		g_object_unref(file);
	} else {
		if (error)
			g_error_free(error);
		webkit_file_chooser_request_cancel(data->request);
	}
	g_object_unref(data->request);
	free(data);
}

static void gtk_file_chooser_open_multiple_finished(GObject *source, GAsyncResult *res, gpointer user_data)
{
	struct gtk_file_chooser_data *data = user_data;
	GError *error = NULL;
	GListModel *model = gtk_file_dialog_open_multiple_finish(GTK_FILE_DIALOG(source), res, &error);
	if (model) {
		guint n = g_list_model_get_n_items(model);
		const gchar **files = g_new0(const gchar *, n + 1);
		gchar **owned = g_new0(gchar *, n + 1);
		for (guint i = 0; i < n; i++) {
			GFile *file = g_list_model_get_item(model, i);
			owned[i] = g_file_get_path(file);
			files[i] = owned[i];
			g_object_unref(file);
		}
		webkit_file_chooser_request_select_files(data->request, files);
		g_strfreev(owned);
		g_free(files);
		g_object_unref(model);
	} else {
		if (error)
			g_error_free(error);
		webkit_file_chooser_request_cancel(data->request);
	}
	g_object_unref(data->request);
	free(data);
}

JNIEXPORT jlong JNICALL Java_android_webkit_WebView_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	/*
	 * many apps use webview just for fingerprinting or displaying ads, which seems like
	 * a waste of resources even if we deal with fingerprinting and ads in some other way
	 * in the future.
	 */
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return Java_android_view_View_native_1constructor(env, this, context, attrs);

	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *webview = webkit_web_view_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), webview);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);

	/* Cockpit and similar local HTTPS use self-signed certs */
	WebKitNetworkSession *session = webkit_web_view_get_network_session(WEBKIT_WEB_VIEW(webview));
	if (session)
		webkit_network_session_set_tls_errors_policy(session, WEBKIT_TLS_ERRORS_POLICY_IGNORE);

	WebKitSettings *settings = webkit_web_view_get_settings(WEBKIT_WEB_VIEW(webview));
	webkit_settings_set_enable_javascript(settings, TRUE);
	webkit_settings_set_enable_html5_local_storage(settings, TRUE);
	webkit_settings_set_javascript_can_open_windows_automatically(settings, TRUE);
	/* Require gesture so pages don't auto-create autoaudiosink on load (WebKit ABRT) */
	webkit_settings_set_media_playback_requires_user_gesture(settings, TRUE);
	webkit_settings_set_hardware_acceleration_policy(settings, WEBKIT_HARDWARE_ACCELERATION_POLICY_ALWAYS);

	webkit_web_context_register_uri_scheme(webkit_web_view_get_context(WEBKIT_WEB_VIEW(webview)), "android-asset", asset_uri_scheme_request_cb, NULL, NULL);
	g_signal_connect(G_OBJECT(webview), "load-changed", G_CALLBACK(web_view_load_changed), NULL);
	g_signal_connect(G_OBJECT(webview), "load-failed", G_CALLBACK(web_view_load_failed), NULL);
	g_signal_connect(G_OBJECT(webview), "load-failed-with-tls-errors", G_CALLBACK(web_view_load_failed_tls), NULL);
	g_signal_connect(G_OBJECT(webview), "permission-request", G_CALLBACK(web_view_permission_request), NULL);
	g_signal_connect(G_OBJECT(webview), "run-file-chooser", G_CALLBACK(web_view_run_file_chooser), NULL);
	g_signal_connect(G_OBJECT(webview), "show-option-menu", G_CALLBACK(web_view_show_option_menu), NULL);
	g_signal_connect(G_OBJECT(webview), "create", G_CALLBACK(web_view_create), NULL);
	g_signal_connect(G_OBJECT(webview), "web-process-terminated", G_CALLBACK(web_view_process_terminated), NULL);
	return _INTPTR(webview);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1loadUrl(JNIEnv *env, jobject this, jlong widget_ptr, jstring url)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return;

	WebKitWebView *webview = _PTR(widget_ptr);
	const char *curl = _CSTRING(url);
	g_message("ATL WebView loadUri %s", curl ? curl : "(null)");
	webkit_web_view_load_uri(webview, curl);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1loadDataWithBaseURL(JNIEnv *env, jobject this, jlong widget_ptr, jstring base_url, jstring data_jstr, jstring mime_type, jstring encoding)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return;

	WebKitWebView *webview = _PTR(widget_ptr);
	jsize data_len = (*env)->GetStringUTFLength(env, data_jstr);
	jsize data_jlen = (*env)->GetStringLength(env, data_jstr);
	char *data = malloc(data_len + 1); // + 1 for NUL
	(*env)->GetStringUTFRegion(env, data_jstr, 0, data_jlen, data);
	webkit_web_view_load_bytes(webview, g_bytes_new(data, data_len), mime_type ? _CSTRING(mime_type) : "text/html", encoding ? _CSTRING(encoding) : NULL, base_url ? _CSTRING(base_url) : NULL);
}

JNIEXPORT jstring JNICALL Java_android_webkit_WebView_native_1getUrl(JNIEnv *env, jobject this, jlong widget_ptr)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return NULL;
	WebKitWebView *webview = _PTR(widget_ptr);
	const gchar *uri = webkit_web_view_get_uri(webview);
	return uri ? _JSTRING(uri) : NULL;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_native_1canGoBack(JNIEnv *env, jobject this, jlong widget_ptr)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return JNI_FALSE;
	return webkit_web_view_can_go_back(_PTR(widget_ptr)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1goBack(JNIEnv *env, jobject this, jlong widget_ptr)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return;
	webkit_web_view_go_back(_PTR(widget_ptr));
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1setJavaScriptEnabled(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enabled)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return;
	WebKitSettings *settings = webkit_web_view_get_settings(_PTR(widget_ptr));
	webkit_settings_set_enable_javascript(settings, enabled);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1setDomStorageEnabled(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enabled)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return;
	WebKitSettings *settings = webkit_web_view_get_settings(_PTR(widget_ptr));
	webkit_settings_set_enable_html5_local_storage(settings, enabled);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1setMediaPlaybackRequiresUserGesture(JNIEnv *env, jobject this, jlong widget_ptr, jboolean require)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return;
	WebKitSettings *settings = webkit_web_view_get_settings(_PTR(widget_ptr));
	webkit_settings_set_media_playback_requires_user_gesture(settings, require);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1evaluateJavascript(JNIEnv *env, jobject this, jlong widget_ptr, jstring script, jobject callback)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !widget_ptr)
		return;
	WebKitWebView *webview = _PTR(widget_ptr);
	const char *cscript = _CSTRING(script);
	struct eval_js_data *data = calloc(1, sizeof(*data));
	data->webview = _REF(this);
	data->callback = callback ? _REF(callback) : NULL;
	g_message("ATL WebView evaluateJavascript len=%zu", cscript ? strlen(cscript) : 0);
	webkit_web_view_evaluate_javascript(webview, cscript, -1, NULL, NULL, NULL,
					    evaluate_javascript_finished, data);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1permissionAllow(JNIEnv *env, jclass clazz, jlong native_request)
{
	WebKitPermissionRequest *request = _PTR(native_request);
	if (!request)
		return;
	webkit_permission_request_allow(request);
	g_object_unref(request);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1permissionDeny(JNIEnv *env, jclass clazz, jlong native_request)
{
	WebKitPermissionRequest *request = _PTR(native_request);
	if (!request)
		return;
	webkit_permission_request_deny(request);
	g_object_unref(request);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1fileChooserSelect(JNIEnv *env, jclass clazz, jlong native_request, jobjectArray uris)
{
	WebKitFileChooserRequest *request = _PTR(native_request);
	if (!request)
		return;
	if (!uris) {
		webkit_file_chooser_request_cancel(request);
		g_object_unref(request);
		return;
	}
	jsize n = (*env)->GetArrayLength(env, uris);
	const gchar **files = g_new0(const gchar *, n + 1);
	gchar **owned = g_new0(gchar *, n + 1);
	jsize out = 0;
	for (jsize i = 0; i < n; i++) {
		jstring juri = (*env)->GetObjectArrayElement(env, uris, i);
		if (!juri)
			continue;
		const char *uri = _CSTRING(juri);
		/* WebKit wants filesystem paths; convert file:// URIs */
		if (g_str_has_prefix(uri, "file://")) {
			GFile *gf = g_file_new_for_uri(uri);
			owned[out] = g_file_get_path(gf);
			g_object_unref(gf);
		} else {
			owned[out] = g_strdup(uri);
		}
		files[out] = owned[out];
		out++;
	}
	files[out] = NULL;
	if (out > 0)
		webkit_file_chooser_request_select_files(request, files);
	else
		webkit_file_chooser_request_cancel(request);
	g_strfreev(owned);
	g_free(files);
	g_object_unref(request);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1fileChooserCancel(JNIEnv *env, jclass clazz, jlong native_request)
{
	WebKitFileChooserRequest *request = _PTR(native_request);
	if (!request)
		return;
	webkit_file_chooser_request_cancel(request);
	g_object_unref(request);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1fileChooserOpenGtk(JNIEnv *env, jclass clazz, jlong native_request, jboolean multiple, jstring mime_type)
{
	WebKitFileChooserRequest *request = _PTR(native_request);
	if (!request)
		return;
	GtkFileDialog *dialog = gtk_file_dialog_new();
	gtk_file_dialog_set_title(dialog, "Open File");
	gtk_file_dialog_set_modal(dialog, TRUE);
	if (mime_type) {
		const char *mime = _CSTRING(mime_type);
		if (mime && !strchr(mime, '*')) {
			GtkFileFilter *filter = gtk_file_filter_new();
			gtk_file_filter_add_mime_type(filter, mime);
			gtk_file_filter_set_name(filter, mime);
			gtk_file_dialog_set_default_filter(dialog, filter);
		}
	}
	struct gtk_file_chooser_data *data = calloc(1, sizeof(*data));
	data->request = request; /* already referenced by Java path */
	if (multiple)
		gtk_file_dialog_open_multiple(dialog, window, NULL, gtk_file_chooser_open_multiple_finished, data);
	else
		gtk_file_dialog_open(dialog, window, NULL, gtk_file_chooser_open_finished, data);
}
