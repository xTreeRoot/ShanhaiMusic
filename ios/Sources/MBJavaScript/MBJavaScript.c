#include "MBJavaScript.h"
#include "vendor/quickjs.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct MBJSRuntime {
    JSRuntime *runtime;
    JSContext *context;
    JSValue exports;
    JSValue pending;
    MBJSHost host;
    void *opaque;
    atomic_bool cancelled;
    double deadline;
    char *result;
    char *error_code;
    char *events[128];
    int event_count;
    int request_id;
};

static double execution_time(void) {
    // 每段 JS 计算按当前线程 CPU 用时限额，避免系统调度/同步宿主 IO 被误判为死循环。
    // 宿主仍独立限制整个异步调用的实际时长，并支持原子取消。
    struct timespec now;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &now) != 0) clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)now.tv_sec + (double)now.tv_nsec / 1e9;
}
static int interrupt(JSRuntime *rt, void *opaque) {
    MBJSRuntime *r = opaque;
    return atomic_load(&r->cancelled) || execution_time() > r->deadline;
}
static void budget(MBJSRuntime *r, int milliseconds) {
    JS_UpdateStackTop(r->runtime);
    r->deadline = execution_time() + (double)(milliseconds > 0 ? milliseconds : 100) / 1000;
}
static void result(MBJSRuntime *r, const char *value) {
    free(r->result); r->result = strdup(value ? value : "null");
    free(r->error_code); r->error_code = NULL;
}
static int failure(MBJSRuntime *r, JSValue value, const char *fallback) {
    const char *message = JS_ToCString(r->context, value);
    result(r, message ? message : fallback);
    JS_FreeCString(r->context, message);
    if (!message) JS_FreeValue(r->context, JS_GetException(r->context));
    if (JS_IsObject(value)) {
        JSValue code = JS_GetPropertyStr(r->context, value, "code");
        if (JS_IsString(code)) {
            const char *text = JS_ToCString(r->context, code);
            if (text) r->error_code = strdup(text);
            else JS_FreeValue(r->context, JS_GetException(r->context));
            JS_FreeCString(r->context, text);
        } else if (JS_IsException(code)) {
            JS_FreeValue(r->context, JS_GetException(r->context));
        }
        JS_FreeValue(r->context, code);
    }
    JS_FreeValue(r->context, value);
    return -1;
}
static int exception(MBJSRuntime *r) {
    return failure(r, JS_GetException(r->context), "JavaScript execution failed");
}
static JSValue host_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    MBJSRuntime *r = JS_GetContextOpaque(ctx);
    if (argc < 2) return JS_ThrowTypeError(ctx, "host operation needs arguments");
    const char *operation = JS_ToCString(ctx, argv[0]);
    JSValue encoded = JS_JSONStringify(ctx, argv[1], JS_UNDEFINED, JS_UNDEFINED);
    const char *arguments = JS_ToCString(ctx, encoded);
    if (!operation || !arguments) {
        JS_FreeCString(ctx, operation); JS_FreeCString(ctx, arguments); JS_FreeValue(ctx, encoded);
        return JS_EXCEPTION;
    }
    char *response = r->host(r->opaque, operation, arguments);
    JS_FreeCString(ctx, operation); JS_FreeCString(ctx, arguments); JS_FreeValue(ctx, encoded);
    if (!response) return JS_ThrowInternalError(ctx, "Host response unavailable");
    JSValue answer = JS_ParseJSON(ctx, response, strlen(response), "host-response");
    free(response); return answer;
}
static JSValue request_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    MBJSRuntime *r = JS_GetContextOpaque(ctx);
    if (argc < 1 || r->event_count >= 128) return JS_ThrowRangeError(ctx, "Too many pending host events");
    JSValue event = JS_DupValue(ctx, argv[0]);
    int id = ++r->request_id;
    JS_SetPropertyStr(ctx, event, "id", JS_NewInt32(ctx, id));
    JSValue encoded = JS_JSONStringify(ctx, event, JS_UNDEFINED, JS_UNDEFINED);
    const char *text = JS_ToCString(ctx, encoded);
    if (text) r->events[r->event_count++] = strdup(text);
    JS_FreeCString(ctx, text); JS_FreeValue(ctx, encoded); JS_FreeValue(ctx, event);
    return text ? JS_NewInt32(ctx, id) : JS_EXCEPTION;
}
static JSModuleDef *module_loader(JSContext *ctx, const char *name, void *opaque) {
    MBJSRuntime *r = opaque;
    JSValue argument = JS_NewArray(ctx);
    JS_SetPropertyUint32(ctx, argument, 0, JS_NewString(ctx, name));
    JSValue values[2] = {JS_NewString(ctx, "module"), argument};
    JSValue response = host_call(ctx, JS_UNDEFINED, 2, values);
    JS_FreeValue(ctx, values[0]); JS_FreeValue(ctx, argument);
    if (JS_IsException(response)) return NULL;
    JSValue error = JS_GetPropertyStr(ctx, response, "error");
    if (!JS_IsUndefined(error)) {
        const char *text = JS_ToCString(ctx, error);
        JS_ThrowReferenceError(ctx, "%s", text ? text : "Module unavailable");
        JS_FreeCString(ctx, text); JS_FreeValue(ctx, error); JS_FreeValue(ctx, response); return NULL;
    }
    JS_FreeValue(ctx, error);
    JSValue source = JS_GetPropertyStr(ctx, response, "value");
    const char *text = JS_ToCString(ctx, source);
    JSValue module = text ? JS_Eval(ctx, text, strlen(text), name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY) : JS_EXCEPTION;
    JS_FreeCString(ctx, text); JS_FreeValue(ctx, source); JS_FreeValue(ctx, response);
    if (JS_IsException(module)) return NULL;
    JSModuleDef *definition = JS_VALUE_GET_PTR(module);
    JS_FreeValue(ctx, module); return definition;
}
MBJSRuntime *mbjs_create(MBJSHost host, void *opaque, size_t memory_limit) {
    MBJSRuntime *r = calloc(1, sizeof(*r));
    if (!r) return NULL;
    r->exports = JS_UNDEFINED; r->pending = JS_UNDEFINED; r->host = host; r->opaque = opaque;
    atomic_init(&r->cancelled, 0);
    r->runtime = JS_NewRuntime();
    if (!r->runtime) { free(r); return NULL; }
    JS_SetMemoryLimit(r->runtime, memory_limit); JS_SetMaxStackSize(r->runtime, 512 * 1024);
    JS_SetInterruptHandler(r->runtime, interrupt, r);
    JS_SetModuleLoaderFunc(r->runtime, NULL, module_loader, r);
    r->context = JS_NewContext(r->runtime);
    if (!r->context) { JS_FreeRuntime(r->runtime); free(r); return NULL; }
    JS_SetContextOpaque(r->context, r);
    JSValue global = JS_GetGlobalObject(r->context);
    JS_SetPropertyStr(r->context, global, "__host", JS_NewCFunction(r->context, host_call, "__host", 2));
    JS_SetPropertyStr(r->context, global, "__request", JS_NewCFunction(r->context, request_call, "__request", 1));
    JS_FreeValue(r->context, global); return r;
}
void mbjs_destroy(MBJSRuntime *r) {
    if (!r) return;
    JS_FreeValue(r->context, r->exports); JS_FreeValue(r->context, r->pending);
    JS_FreeContext(r->context); JS_FreeRuntime(r->runtime);
    for (int i = 0; i < r->event_count; i++) free(r->events[i]);
    free(r->result); free(r->error_code); free(r);
}
void mbjs_cancel(MBJSRuntime *r) { if (r) atomic_store(&r->cancelled, 1); }
int mbjs_evaluate(MBJSRuntime *r, const char *source, const char *name, int module, int milliseconds) {
    budget(r, milliseconds);
    JSValue value = JS_Eval(r->context, source, strlen(source), name,
                           module ? JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY : JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(value)) return exception(r);
    if (module) {
        JSValue compiled = JS_DupValue(r->context, value);
        value = JS_EvalFunction(r->context, value);
        if (!JS_IsException(value)) {
            JSValue exports = JS_GetModuleNamespace(r->context, JS_VALUE_GET_PTR(compiled));
            if (JS_IsException(exports)) {
                JS_FreeValue(r->context, compiled); JS_FreeValue(r->context, value); return exception(r);
            }
            JS_FreeValue(r->context, r->exports); r->exports = exports;
        }
        JS_FreeValue(r->context, compiled);
    }
    if (JS_IsException(value)) return exception(r);
    JS_FreeValue(r->context, r->pending); r->pending = value;
    return mbjs_poll(r, milliseconds);
}
int mbjs_start(MBJSRuntime *r, const char *name, const char *arguments, int milliseconds) {
    budget(r, milliseconds);
    JSValue function = JS_GetPropertyStr(r->context, r->exports, name);
    if (!JS_IsFunction(r->context, function)) {
        JS_FreeValue(r->context, function); result(r, "Plugin does not export this capability"); return -1;
    }
    JSValue argument = JS_ParseJSON(r->context, arguments, strlen(arguments), "arguments");
    JSValue value = JS_IsException(argument) ? JS_EXCEPTION : JS_Call(r->context, function, JS_UNDEFINED, 1, &argument);
    JS_FreeValue(r->context, function); JS_FreeValue(r->context, argument);
    if (JS_IsException(value)) return exception(r);
    JS_FreeValue(r->context, r->pending); r->pending = value;
    return mbjs_poll(r, milliseconds);
}
int mbjs_poll(MBJSRuntime *r, int milliseconds) {
    budget(r, milliseconds);
    JSContext *ctx = NULL;
    int jobs = 0;
    while (JS_IsJobPending(r->runtime)) {
        if (++jobs > 4096 || interrupt(r->runtime, r)) { result(r, "JavaScript execution budget exceeded"); return -1; }
        if (JS_ExecutePendingJob(r->runtime, &ctx) < 0) return exception(r);
    }
    JSPromiseStateEnum state = JS_PromiseState(r->context, r->pending);
    if (state == JS_PROMISE_PENDING) return 0;
    JSValue value = state == JS_PROMISE_FULFILLED || state == JS_PROMISE_REJECTED ? JS_PromiseResult(r->context, r->pending) : JS_DupValue(r->context, r->pending);
    if (state == JS_PROMISE_REJECTED) {
        return failure(r, value, "Plugin promise rejected");
    }
    JSValue json = JS_JSONStringify(r->context, value, JS_UNDEFINED, JS_UNDEFINED);
    JS_FreeValue(r->context, value);
    if (JS_IsException(json)) return exception(r);
    const char *text = JS_IsUndefined(json) ? NULL : JS_ToCString(r->context, json);
    result(r, text); JS_FreeCString(r->context, text); JS_FreeValue(r->context, json); return 1;
}
int mbjs_settle(MBJSRuntime *r, int request, int success, const char *json, int milliseconds) {
    budget(r, milliseconds);
    JSValue global = JS_GetGlobalObject(r->context);
    JSValue function = JS_GetPropertyStr(r->context, global, "__settle");
    JSValue arguments[3] = {JS_NewInt32(r->context, request), JS_NewBool(r->context, success), JS_ParseJSON(r->context, json, strlen(json), "host-event")};
    JSValue returned = JS_Call(r->context, function, JS_UNDEFINED, 3, arguments);
    for (int i = 0; i < 3; i++) JS_FreeValue(r->context, arguments[i]);
    JS_FreeValue(r->context, function); JS_FreeValue(r->context, global);
    if (JS_IsException(returned)) return exception(r);
    JS_FreeValue(r->context, returned); return mbjs_poll(r, milliseconds);
}
char *mbjs_take_event(MBJSRuntime *r) {
    if (!r->event_count) return NULL;
    char *event = r->events[0];
    memmove(r->events, r->events + 1, (size_t)--r->event_count * sizeof(char *)); return event;
}
char *mbjs_result(MBJSRuntime *r) { return strdup(r->result ? r->result : "null"); }
char *mbjs_error_code(MBJSRuntime *r) { return r->error_code ? strdup(r->error_code) : NULL; }
void mbjs_free_string(char *value) { free(value); }

int mbjs_has_export(MBJSRuntime *r, const char *name) {
    JSValue value = JS_GetPropertyStr(r->context, r->exports, name);
    int found = JS_IsFunction(r->context, value);
    JS_FreeValue(r->context, value); return found;
}
