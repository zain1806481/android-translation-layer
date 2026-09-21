#include <gtk/gtk.h>
#include <libsoup/soup.h>
#include <webkit/webkit.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_webkit_CookieManager.h"

static WebKitCookieManager *atl_cookie_manager(void)
{
	WebKitNetworkSession *session = webkit_network_session_get_default();
	if (!session)
		return NULL;
	return webkit_network_session_get_cookie_manager(session);
}

struct cookie_op {
	GMainLoop *loop;
	gboolean ok;
};

static void add_cookie_done(GObject *source, GAsyncResult *result, gpointer user_data)
{
	struct cookie_op *op = user_data;
	GError *error = NULL;
	op->ok = webkit_cookie_manager_add_cookie_finish(WEBKIT_COOKIE_MANAGER(source), result, &error);
	if (error) {
		g_warning("ATL CookieManager add_cookie failed: %s", error->message);
		g_error_free(error);
		op->ok = FALSE;
	}
	g_main_loop_quit(op->loop);
}

static void replace_cookies_done(GObject *source, GAsyncResult *result, gpointer user_data)
{
	struct cookie_op *op = user_data;
	GError *error = NULL;
	op->ok = webkit_cookie_manager_replace_cookies_finish(WEBKIT_COOKIE_MANAGER(source), result, &error);
	if (error) {
		g_warning("ATL CookieManager replace_cookies failed: %s", error->message);
		g_error_free(error);
		op->ok = FALSE;
	}
	g_main_loop_quit(op->loop);
}

JNIEXPORT void JNICALL Java_android_webkit_CookieManager_native_1setCookie(JNIEnv *env, jclass clazz, jstring url, jstring value)
{
	WebKitCookieManager *cm = atl_cookie_manager();
	if (!cm || !url || !value)
		return;
	const char *curl = (*env)->GetStringUTFChars(env, url, NULL);
	const char *cvalue = (*env)->GetStringUTFChars(env, value, NULL);
	GUri *origin = g_uri_parse(curl, G_URI_FLAGS_NONE, NULL);
	SoupCookie *cookie = soup_cookie_parse(cvalue, origin);
	if (cookie) {
		struct cookie_op op = {0};
		op.loop = g_main_loop_new(NULL, FALSE);
		webkit_cookie_manager_add_cookie(cm, cookie, NULL, add_cookie_done, &op);
		g_main_loop_run(op.loop);
		g_main_loop_unref(op.loop);
		soup_cookie_free(cookie);
	} else {
		g_warning("ATL CookieManager setCookie parse failed url=%s", curl);
	}
	if (origin)
		g_uri_unref(origin);
	(*env)->ReleaseStringUTFChars(env, url, curl);
	(*env)->ReleaseStringUTFChars(env, value, cvalue);
}

JNIEXPORT void JNICALL Java_android_webkit_CookieManager_native_1removeAll(JNIEnv *env, jclass clazz)
{
	WebKitCookieManager *cm = atl_cookie_manager();
	if (!cm)
		return;
	struct cookie_op op = {0};
	op.loop = g_main_loop_new(NULL, FALSE);
	webkit_cookie_manager_replace_cookies(cm, NULL, NULL, replace_cookies_done, &op);
	g_main_loop_run(op.loop);
	g_main_loop_unref(op.loop);
}
