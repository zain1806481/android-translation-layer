#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"

#include "../generated_headers/android_widget_PopupWindow.h"

JNIEXPORT jlong JNICALL Java_android_widget_PopupWindow_native_1constructor(JNIEnv *env, jobject this)
{
	GtkWidget *popover = gtk_popover_new();
	gtk_widget_set_name(popover, "PopupWindow");
	/* autohiding works by the widget grabbing events, which is not something apps expect */
	gtk_popover_set_autohide(GTK_POPOVER(popover), false);
	return _INTPTR(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setContentView(JNIEnv *env, jobject this, jlong popover_ptr, jlong content_ptr)
{
	WrapperWidget *content = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(_PTR(content_ptr))));
	gtk_popover_set_child(GTK_POPOVER(_PTR(popover_ptr)), GTK_WIDGET(content));
}

static inline void set_offset(GtkPopover *popover, GtkWidget *anchor, int x, int y)
{
	/* FIXME: assumes GTK_POS_BOTTOM */
	gtk_popover_set_offset(popover, x - gtk_widget_get_width(anchor) / 2, y - gtk_widget_get_height(anchor));
	gtk_popover_set_pointing_to(popover, &(GdkRectangle){.x = 0, .y = 0, .width = gtk_widget_get_width(anchor), .height = gtk_widget_get_height(anchor)});
}

extern GtkWindow *window;

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1showAsDropDown(JNIEnv *env, jobject this, jlong popover_ptr, jlong anchor_ptr, jint x, jint y, jint gravity)
{
	(void)env;
	(void)this;
	(void)gravity;
	GtkPopover *popover = GTK_POPOVER(_PTR(popover_ptr));
	GtkWidget *anchor_inner = GTK_WIDGET(_PTR(anchor_ptr));
	GtkWidget *anchor_wrap = gtk_widget_get_parent(anchor_inner);
	if (!GTK_IS_WIDGET(anchor_wrap) || !window) {
		g_warning("ATL PopupWindow showAsDropDown: missing anchor/window");
		return;
	}

	GtkWidget *root_child = gtk_window_get_child(window);
	if (!root_child) {
		g_warning("ATL PopupWindow showAsDropDown: window has no child");
		return;
	}

	/* Position relative to window content (same pattern as WindowManagerImpl) */
	double ax = 0, ay = 0;
	if (!gtk_widget_translate_coordinates(anchor_wrap, root_child, 0, 0, &ax, &ay)) {
		ax = 0;
		ay = 0;
	}
	int aw = gtk_widget_get_width(anchor_wrap);
	int ah = gtk_widget_get_height(anchor_wrap);
	if (aw <= 0)
		aw = WRAPPER_IS_WIDGET(anchor_wrap) ? WRAPPER_WIDGET(anchor_wrap)->real_width : 46;
	if (ah <= 0)
		ah = WRAPPER_IS_WIDGET(anchor_wrap) ? WRAPPER_WIDGET(anchor_wrap)->real_height : 46;
	if (aw <= 0)
		aw = 46;
	if (ah <= 0)
		ah = 46;
	/* Point at the anchor; Android drop-down is below-left via x/y offset */
	GdkRectangle rect = {
		.x = (int)ax,
		.y = (int)ay,
		.width = aw,
		.height = ah,
	};

	GtkWidget *prev = gtk_widget_get_parent(GTK_WIDGET(popover));
	if (prev && prev != root_child)
		gtk_widget_unparent(GTK_WIDGET(popover));
	if (!gtk_widget_get_parent(GTK_WIDGET(popover)))
		gtk_widget_insert_before(GTK_WIDGET(popover), root_child, NULL);

	/*
	 * Size: honor explicit requests; for WRAP_CONTENT (-1 after setWidth/Height)
	 * measure the content so we do not clip rows (forced 420 cut off App settings /
	 * Hide header).
	 */
	int req_w = -1, req_h = -1;
	gtk_widget_get_size_request(GTK_WIDGET(popover), &req_w, &req_h);
	if (req_w < 0)
		req_w = 230;

	GtkWidget *child = gtk_popover_get_child(popover);
	int use_w = req_w;
	int use_h = req_h;
	if (child) {
		gtk_widget_set_visible(child, TRUE);
		/* Clear bogus -2 left on the wrapper; let measure run */
		int cw = -1, ch = -1;
		gtk_widget_get_size_request(child, &cw, &ch);
		if (cw < 0)
			cw = use_w;
		if (ch < 0)
			ch = -1;
		gtk_widget_set_size_request(child, cw, ch);

		int min_h = 0, nat_h = 0;
		gtk_widget_measure(child, GTK_ORIENTATION_VERTICAL, use_w, &min_h, &nat_h, NULL, NULL);
		if (use_h < 0) {
			use_h = nat_h > min_h ? nat_h : min_h;
			if (use_h < 48) {
				GtkRequisition min_r, nat_r;
				gtk_widget_get_preferred_size(child, &min_r, &nat_r);
				use_h = nat_r.height > 0 ? nat_r.height : min_r.height;
			}
		}
		if (WRAPPER_IS_WIDGET(child)) {
			WrapperWidget *ww = WRAPPER_WIDGET(child);
			if (ww->real_height > use_h)
				use_h = ww->real_height;
		}
	}
	if (use_h < 48)
		use_h = 560;

	g_message("ATL PopupWindow showAsDropDown x=%d y=%d anchor=%d,%d %dx%d size=%dx%d",
		  x, y, rect.x, rect.y, rect.width, rect.height, use_w, use_h);

	gtk_widget_set_size_request(GTK_WIDGET(popover), use_w, use_h);
	if (child) {
		gtk_widget_set_size_request(child, use_w, use_h);
		if (WRAPPER_IS_WIDGET(child)) {
			WRAPPER_WIDGET(child)->real_width = use_w;
			WRAPPER_WIDGET(child)->real_height = use_h;
		}
		gtk_widget_queue_allocate(child);
	}

	gtk_popover_set_autohide(popover, FALSE);
	gtk_popover_set_has_arrow(popover, FALSE);
	gtk_popover_set_position(popover, GTK_POS_BOTTOM);
	gtk_popover_set_pointing_to(popover, &rect);
	/* Nudge to match Android showAsDropDown(anchor, xoff, yoff) */
	gtk_popover_set_offset(popover, x + aw / 2, y);
	gtk_popover_present(popover);
	gtk_popover_popup(popover);
	gtk_widget_set_visible(GTK_WIDGET(popover), TRUE);
	gtk_widget_queue_allocate(root_child);
	gtk_widget_queue_draw(GTK_WIDGET(popover));
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setWidth(JNIEnv *env, jobject this, jlong popover_ptr, jint width)
{
	(void)env;
	(void)this;
	int height;
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_get_size_request(popover, NULL, &height);
	/* Android WRAP_CONTENT=-2 / MATCH_PARENT=-1 → let GTK use natural size */
	if (width < 0)
		width = -1;
	if (height < 0)
		height = -1;
	gtk_widget_set_size_request(popover, width, height);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setHeight(JNIEnv *env, jobject this, jlong popover_ptr, jint height)
{
	(void)env;
	(void)this;
	int width;
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_get_size_request(popover, &width, NULL);
	if (width < 0)
		width = -1;
	if (height < 0)
		height = -1;
	gtk_widget_set_size_request(popover, width, height);
}

JNIEXPORT jint JNICALL Java_android_widget_PopupWindow_native_1getWidth(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	GtkRequisition natural_size;
	gtk_widget_get_preferred_size(popover, &natural_size, NULL);
	return natural_size.width;
}

JNIEXPORT jint JNICALL Java_android_widget_PopupWindow_native_1getHeight(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	GtkRequisition natural_size;
	gtk_widget_get_preferred_size(popover, &natural_size, NULL);
	return natural_size.height;
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setTouchable(JNIEnv *env, jobject this, jlong popover_ptr, jboolean touchable)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_set_sensitive(popover, touchable);
}

JNIEXPORT jboolean JNICALL Java_android_widget_PopupWindow_native_1isTouchable(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	return gtk_widget_is_sensitive(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setTouchModal(JNIEnv *env, jobject this, jlong popover_ptr, jboolean touch_modal)
{
	GtkPopover *popover = GTK_POPOVER(_PTR(popover_ptr));
	/* FIXME: we should only add grab (not autohide), however we need to remove it again in umap;
	 * GtkPopover is not final, so we should subclass it and check whether it's modal in map/unmap
	 * to add/remove grab, which is the desired part of what GtkPopover does with autohide enabled */
	gtk_popover_set_autohide(popover, touch_modal);
}

static void on_closed_cb(GtkPopover *popover, jobject listener)
{
	JNIEnv *env = get_jni_env();
	jmethodID onDismiss = _METHOD(_CLASS(listener), "onDismiss", "()V");
	(*env)->CallVoidMethod(env, listener, onDismiss);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_setOnDismissListener(JNIEnv *env, jobject this, jobject listener)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "popover")));
	g_signal_connect(popover, "closed", G_CALLBACK(on_closed_cb), _REF(listener));
}

JNIEXPORT jboolean JNICALL Java_android_widget_PopupWindow_native_1isShowing(JNIEnv *env, jobject this, jlong popover_ptr)
{
	(void)env;
	(void)this;
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	return gtk_widget_get_mapped(popover) && gtk_widget_get_visible(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1dismiss(JNIEnv *env, jobject this, jlong popover_ptr)
{
	gtk_popover_popdown(GTK_POPOVER(_PTR(popover_ptr)));
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1update(JNIEnv *env, jobject this, jlong popover_ptr, jlong anchor_ptr, jint x, jint y, jint width, jint height)
{
	GtkPopover *popover = GTK_POPOVER(_PTR(popover_ptr));
	WrapperWidget *anchor = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(_PTR(anchor_ptr))));
	gtk_widget_set_size_request(GTK_WIDGET(popover), width, height);
	set_offset(popover, GTK_WIDGET(anchor), x, y);
	gtk_widget_insert_before(GTK_WIDGET(popover), GTK_WIDGET(anchor), NULL);
	gtk_popover_present(GTK_POPOVER(popover));
	gtk_popover_popup(popover);
}
