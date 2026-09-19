#pragma once
#include <glib-object.h>

void g_cclosure_user_marshal_VOID__INT_INT(GClosure *, GValue *, guint,
                                           const GValue *, gpointer, gpointer);
void g_cclosure_user_marshal_VOID__INT_INTv(GClosure *, GValue *, gpointer,
                                            va_list, gpointer, int, GType *);
