package com.shiraka.wereposition.xposed

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap

// --- Legacy Compatibility Layer ---
abstract class XC_MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    class MethodHookParam {
        var method: Member? = null
        var thisObject: Any? = null
        var args: Array<Any?> = emptyArray()
        var returnEarly = false
        var result: Any? = null
            set(value) {
                field = value
                returnEarly = true
            }
        var throwable: Throwable? = null
            set(value) {
                field = value
                returnEarly = true
            }
    }
}

object XposedHelpers {
    lateinit var module: XposedModule

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try { findClass(className, classLoader) } catch (e: Throwable) { null }
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setIntField(obj: Any, fieldName: String, value: Int) { setObjectField(obj, fieldName, value) }
    fun setDoubleField(obj: Any, fieldName: String, value: Double) { setObjectField(obj, fieldName, value) }
    fun setFloatField(obj: Any, fieldName: String, value: Float) { setObjectField(obj, fieldName, value) }
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) { setObjectField(obj, fieldName, value) }
    fun setLongField(obj: Any, fieldName: String, value: Long) { setObjectField(obj, fieldName, value) }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (m in clazz.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun tryCallMethodDeep(obj: Any, methodName: String, vararg args: Any?): Boolean {
        val visited = HashSet<Class<*>>()
        val stack = ArrayDeque<Class<*>>()
        stack.add(obj.javaClass)
        while (stack.isNotEmpty()) {
            val clazz = stack.removeFirst()
            if (!visited.add(clazz)) continue
            for (m in clazz.declaredMethods) {
                if (m.name != methodName || m.parameterCount != args.size) continue
                val types = m.parameterTypes
                var compatible = true
                for (i in args.indices) {
                    if (!isCompatibleParameter(types[i], args[i])) {
                        compatible = false
                        break
                    }
                }
                if (!compatible) continue
                try {
                    m.isAccessible = true
                    m.invoke(obj, *args)
                    return true
                } catch (_: Throwable) {
                }
            }
            clazz.superclass?.let { stack.add(it) }
            clazz.interfaces.forEach { stack.add(it) }
        }
        return false
    }

    private fun isCompatibleParameter(parameterType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !parameterType.isPrimitive
        val argType = arg.javaClass
        if (parameterType.isAssignableFrom(argType)) return true
        if (!parameterType.isPrimitive) return false
        return when (parameterType) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            java.lang.Short.TYPE -> arg is Short
            java.lang.Byte.TYPE -> arg is Byte
            java.lang.Character.TYPE -> arg is Char
            else -> false
        }
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    return m.invoke(null, *args)
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(methodName, *parameterTypes)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(methodName)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (c in clazz.declaredConstructors) {
            if (c.parameterCount == args.size) {
                c.isAccessible = true
                return c.newInstance(*args)
            }
        }
        throw NoSuchMethodException("Constructor for " + clazz.name + " not found")
    }

    fun findAndHookMethod(className: String, classLoader: ClassLoader?, methodName: String, vararg args: Any?) {
        try {
            val clazz = findClass(className, classLoader)
            findAndHookMethod(clazz, methodName, *args)
        } catch (e: Throwable) {
            // log
        }
    }

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg args: Any?) {
        val hookIndex = args.indexOfLast { it is XC_MethodHook }
        if (hookIndex == -1) return
        val callback = args[hookIndex] as XC_MethodHook
        val paramTypes = args.slice(0 until hookIndex).map {
            when (it) {
                is Class<*> -> it
                is String -> findClass(it, clazz.classLoader)
                else -> throw IllegalArgumentException("Invalid argument type")
            }
        }.toTypedArray()

        val method = findMethodExact(clazz, methodName, *paramTypes)
        hookMethod(method, callback)
    }

    fun hookMethod(executable: java.lang.reflect.Executable, callback: XC_MethodHook) {
        module.hook(executable).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
            override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                val param = XC_MethodHook.MethodHookParam().apply {
                    this.method = executable
                    this.thisObject = chain.thisObject
                    this.args = chain.args.toTypedArray()
                }

                val beforeArgs = param.args.copyOf()
                val beforeResult = param.result
                val beforeThrowable = param.throwable
                val beforeReturnEarly = param.returnEarly
                try {
                    callback.beforeHookedMethod(param)
                } catch (e: Throwable) {
                    // A compatibility callback is module code, not part of the hooked method.
                    // Never turn a callback bug into an exception in the host process (and, in
                    // particular, never let it crash system_server). Restore the complete state
                    // from before the callback and continue with the original method.
                    param.args = beforeArgs
                    param.result = beforeResult
                    param.throwable = beforeThrowable
                    param.returnEarly = beforeReturnEarly
                    logCallbackFailure("before", executable, e)
                }

                if (!param.returnEarly) {
                    try {
                        param.result = chain.proceed(param.args)
                    } catch (e: Throwable) {
                        param.throwable = e
                    }
                }

                val afterArgs = param.args.copyOf()
                val afterResult = param.result
                val afterThrowable = param.throwable
                val afterReturnEarly = param.returnEarly
                try {
                    callback.afterHookedMethod(param)
                } catch (e: Throwable) {
                    // Preserve the original method's result/throwable when an after callback
                    // fails. This matches the isolation expected from Xposed callbacks.
                    param.args = afterArgs
                    param.result = afterResult
                    param.throwable = afterThrowable
                    param.returnEarly = afterReturnEarly
                    logCallbackFailure("after", executable, e)
                }

                if (param.throwable != null) throw param.throwable!!
                return param.result
            }
        })
    }

    private fun logCallbackFailure(
        phase: String,
        executable: java.lang.reflect.Executable,
        error: Throwable
    ) {
        try {
            val message = "Hook $phase callback failed for ${executable.declaringClass.name}.${executable.name}"
            XposedBridge.logEvery(
                "callback-failure:$phase:${executable.declaringClass.name}.${executable.name}:${error.javaClass.name}",
                "$message: ${error.javaClass.simpleName}: ${error.message}",
                60_000L
            )
        } catch (_: Throwable) {
            // Logging must never become another host-process failure path.
        }
    }
}

object XposedBridge {
    private val openCellLogLastTimes = ConcurrentHashMap<String, Long>()
    private val generalLogLastTimes = ConcurrentHashMap<String, Long>()
    private val dynamicLogToken = Regex("-?\\d+(?:\\.\\d+)?")
    private const val MAX_LOG_KEYS = 256

    fun log(msg: String) {
        android.util.Log.i("WeRePosition_Xposed", msg)
    }
    fun logEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = generalLogLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (generalLogLastTimes.size >= MAX_LOG_KEYS && last == null) {
                generalLogLastTimes.clear()
            }
            generalLogLastTimes[key] = now
            log(msg)
        }
    }
    fun logOpenCellId(msg: String) {
        val key = dynamicLogToken.replace(msg, "#").take(160)
        logOpenCellIdEvery("auto:$key", msg, 60_000L)
    }
    fun logOpenCellId(msg: String, t: Throwable) {
        val key = dynamicLogToken.replace(msg, "#").take(120)
        logOpenCellIdEvery(
            "error:$key:${t.javaClass.name}",
            "$msg: ${t.javaClass.simpleName}: ${t.message}",
            60_000L
        )
    }
    fun logOpenCellIdEvery(key: String, msg: String, intervalMs: Long = 60_000L) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = openCellLogLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            if (openCellLogLastTimes.size >= MAX_LOG_KEYS && last == null) {
                openCellLogLastTimes.clear()
            }
            openCellLogLastTimes[key] = now
            android.util.Log.d("OpenCellID", "[XposedCell] $msg")
        }
    }
    fun log(t: Throwable) {
        val site = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}" } ?: "unknown"
        logEvery(
            "throwable:${t.javaClass.name}:$site",
            "[WeRePosition] ${t.javaClass.simpleName}: ${t.message}",
            60_000L
        )
    }
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook) {
        for (m in clazz.declaredMethods) {
            if (m.name == methodName) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                XposedHelpers.hookMethod(m, callback)
            }
        }
    }
}
