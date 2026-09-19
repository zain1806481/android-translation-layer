#include <glib-object.h>

void g_cclosure_user_marshal_VOID__INT_INT(GClosure *closure,
                                           GValue *return_value,
                                           guint n_param_values,
                                           const GValue *param_values,
                                           gpointer invocation_hint,
                                           gpointer marshal_data)
{
    typedef void (*Callback)(gpointer, gint, gint, gpointer);
    Callback callback = (Callback)(marshal_data ? marshal_data : ((GCClosure *)closure)->callback);
    if (n_param_values >= 3)
        callback(g_value_get_object(&param_values[0]), g_value_get_int(&param_values[1]),
                 g_value_get_int(&param_values[2]), closure->data);
}

void g_cclosure_user_marshal_VOID__INT_INTv(GClosure *closure, GValue *return_value,
                                            gpointer instance, va_list args,
                                            gpointer marshal_data, int n_params,
                                            GType *param_types)
{
    typedef void (*Callback)(gpointer, gint, gint, gpointer);
    Callback callback = (Callback)(marshal_data ? marshal_data : ((GCClosure *)closure)->callback);
    callback(instance, va_arg(args, gint), va_arg(args, gint), closure->data);
}
