#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"

#include "../generated_headers/android_widget_EditText.h"

JNIEXPORT jlong JNICALL Java_android_widget_EditText_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *gtk_text = gtk_text_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), gtk_text);
	return _INTPTR(gtk_text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getText(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_entry_buffer_get_text(gtk_text_get_buffer(gtk_text));
	return _JSTRING(text);
}

struct changed_callback_data {
	jobject this;
	jobject listener;
	jmethodID listener_method;
	jmethodID getText;
};

/* GtkEditable offsets are Unicode characters; Android uses UTF-16 offsets. */
static int utf16_length(const char *text, int chars)
{
	int length = 0;
	for (const char *p = text; *p && chars != 0; p = g_utf8_next_char(p), chars--)
		length += g_utf8_get_char(p) > 0xffff ? 2 : 1;
	return length;
}

static void before_change(GtkEditable *self, int start, int before, int count)
{
	JNIEnv *env = get_jni_env();
	g_object_set_data(G_OBJECT(self), "change_start", GINT_TO_POINTER(start));
	g_object_set_data(G_OBJECT(self), "change_before", GINT_TO_POINTER(before));
	g_object_set_data(G_OBJECT(self), "change_count", GINT_TO_POINTER(count));
	(*env)->PushLocalFrame(env, 16);
	jstring text = _JSTRING(gtk_editable_get_text(self));
	GList *listeners = g_object_get_data(G_OBJECT(self), "text_changed_listeners");
	for (GList *l = listeners; l; l = l->next) {
		jclass klass = _CLASS(l->data);
		jmethodID method = _METHOD(klass, "beforeTextChanged", "(Ljava/lang/CharSequence;III)V");
		(*env)->CallVoidMethod(env, l->data, method, text, start, before, count);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
		(*env)->DeleteLocalRef(env, klass);
	}
	(*env)->PopLocalFrame(env, NULL);
}

static void inserting_cb(GtkEditable *self, const char *text, int length, int *position, gpointer data)
{
	char *inserted = g_strndup(text, length);
	before_change(self, utf16_length(gtk_editable_get_text(self), *position), 0, utf16_length(inserted, -1));
	g_free(inserted);
}

static void deleting_cb(GtkEditable *self, int start, int end, gpointer data)
{
	const char *text = gtk_editable_get_text(self);
	int first = utf16_length(text, start);
	before_change(self, first, utf16_length(text, end) - first, 0);
}

static void changed_cb(GtkEditable *self, jobject listener)
{
	JNIEnv *env = get_jni_env();

	if ((*env)->ExceptionCheck(env))
		return;
	(*env)->PushLocalFrame(env, 16);
	const char *text = gtk_editable_get_text(self);
	jclass spannable_string_builder = (*env)->FindClass(env, "android/text/SpannableStringBuilder");
	jmethodID spannable_string_builder_constructor = _METHOD(spannable_string_builder, "<init>", "(Ljava/lang/CharSequence;)V");
	jobject text_obj = (*env)->NewObject(env, spannable_string_builder, spannable_string_builder_constructor, _JSTRING(text));
	jmethodID onTextChanged = _METHOD(_CLASS(listener), "onTextChanged", "(Ljava/lang/CharSequence;III)V");
	(*env)->CallVoidMethod(env, listener, onTextChanged, text_obj,
	    GPOINTER_TO_INT(g_object_get_data(G_OBJECT(self), "change_start")),
	    GPOINTER_TO_INT(g_object_get_data(G_OBJECT(self), "change_before")),
	    GPOINTER_TO_INT(g_object_get_data(G_OBJECT(self), "change_count")));
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
		(*env)->PopLocalFrame(env, NULL);
		return;
	}
	jmethodID listener_method = _METHOD(_CLASS(listener), "afterTextChanged", "(Landroid/text/Editable;)V");
	(*env)->CallVoidMethod(env, listener, listener_method, text_obj);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	(*env)->PopLocalFrame(env, NULL);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1addTextChangedListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	listener = _REF(listener);

	GList *listeners = g_object_get_data(G_OBJECT(gtk_text), "text_changed_listeners");
	if (!g_object_get_data(G_OBJECT(gtk_text), "change_signals_connected")) {
		g_signal_connect(gtk_text, "insert-text", G_CALLBACK(inserting_cb), NULL);
		g_signal_connect(gtk_text, "delete-text", G_CALLBACK(deleting_cb), NULL);
		g_object_set_data(G_OBJECT(gtk_text), "change_signals_connected", GINT_TO_POINTER(1));
	}
	listeners = g_list_append(listeners, listener);
	g_object_set_data(G_OBJECT(gtk_text), "text_changed_listeners", listeners);
	g_signal_connect(GTK_EDITABLE(gtk_text), "changed", G_CALLBACK(changed_cb), listener);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1removeTextChangedListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));

	GList *listeners = g_object_get_data(G_OBJECT(gtk_text), "text_changed_listeners");
	GList *l;
	for (l = listeners; l != NULL; l = l->next) {
		if ((*env)->IsSameObject(env, l->data, listener)) {
			g_signal_handlers_disconnect_by_func(GTK_EDITABLE(gtk_text), changed_cb, l->data);
			_UNREF(l->data);
			listeners = g_list_delete_link(listeners, l);
			break;
		}
	}
	g_object_set_data(G_OBJECT(gtk_text), "text_changed_listeners", listeners);
}

#define IME_ACTION_SEARCH 3
#define KEYCODE_ENTER     66

static void on_activate(GtkText *gtk_text, struct changed_callback_data *d)
{
	JNIEnv *env = get_jni_env();

	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor, (jlong)0, (jlong)0, IME_ACTION_SEARCH, KEYCODE_ENTER, 0, 0);
	(*env)->CallBooleanMethod(env, d->listener, d->listener_method, d->this, 0, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setOnEditorActionListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));

	if (!listener)
		return;

	struct changed_callback_data *callback_data = malloc(sizeof(struct changed_callback_data));
	callback_data->this = _WEAK_REF(this);
	callback_data->listener = _REF(listener);
	callback_data->listener_method = _METHOD(_CLASS(listener), "onEditorAction", "(Landroid/widget/TextView;ILandroid/view/KeyEvent;)Z");

	g_signal_handlers_disconnect_matched(gtk_text, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, on_activate, NULL);
	g_signal_connect(gtk_text, "activate", G_CALLBACK(on_activate), callback_data);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	const char *gtk_text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	jsize length = (*env)->GetStringUTFLength(env, text_jstr);
	gtk_entry_buffer_set_text(gtk_text_get_buffer(GTK_TEXT(_PTR(widget_ptr))), gtk_text, length);
	(*env)->ReleaseStringUTFChars(env, text_jstr, gtk_text);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setHint(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	const char *text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	gtk_text_set_placeholder_text(GTK_TEXT(_PTR(widget_ptr)), text);
	(*env)->ReleaseStringUTFChars(env, text_jstr, text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getHint(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_text_get_placeholder_text(gtk_text);
	return _JSTRING(text);
}
