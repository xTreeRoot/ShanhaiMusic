#ifndef MB_JAVASCRIPT_H
#define MB_JAVASCRIPT_H
#include <stddef.h>
#include <stdint.h>

typedef struct MBJSRuntime MBJSRuntime;
/* Callback returns malloc-owned JSON envelope: {value:...} or {error:string}. */
typedef char *(*MBJSHost)(void *opaque, const char *operation, const char *arguments);
MBJSRuntime *mbjs_create(MBJSHost host, void *opaque, size_t memory_limit);
void mbjs_destroy(MBJSRuntime *runtime);
/* Only mbjs_cancel may run concurrently with other operations. */
void mbjs_cancel(MBJSRuntime *runtime);
int mbjs_evaluate(MBJSRuntime *runtime, const char *source, const char *name, int module, int budget_ms);
int mbjs_start(MBJSRuntime *runtime, const char *name, const char *arguments, int budget_ms);
int mbjs_poll(MBJSRuntime *runtime, int budget_ms);
int mbjs_settle(MBJSRuntime *runtime, int request, int success, const char *json, int budget_ms);
int mbjs_has_export(MBJSRuntime *runtime, const char *name);
char *mbjs_take_event(MBJSRuntime *runtime);
char *mbjs_result(MBJSRuntime *runtime);
char *mbjs_error_code(MBJSRuntime *runtime);
void mbjs_free_string(char *string);
#endif
