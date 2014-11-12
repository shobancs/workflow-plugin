/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.structs;

import hudson.Extension;
import com.google.common.primitives.Primitives;
import hudson.model.Describable;
import hudson.model.Descriptor;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.CheckForNull;
import jenkins.model.Jenkins;
import net.java.sezpoz.Index;
import net.java.sezpoz.IndexItem;
import org.codehaus.groovy.reflection.ReflectionCache;
import org.kohsuke.stapler.ClassDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.NoStaplerConstructorException;

/**
 * Utility for converting between {@link Describable}s (and some other objects) and map-like representations.
 * Ultimately should live in Jenkins core (or Stapler).
 */
public class DescribableHelper {

    /**
     * Creates an instance of a class via {@link DataBoundConstructor} and {@link DataBoundSetter}.
     * <p>The arguments may be primitives (as wrappers) or {@link String}s if that is their declared type.
     * {@link Character}s, {@link Enum}s, and {@link URL}s may be represented by {@link String}s.
     * Other object types may be passed in “raw” as well, but JSON-like structures are encouraged instead.
     * Specifically a {@link List} may be used to represent any list- or array-valued argument.
     * A {@link Map} with {@link String} keys may be used to represent any class which is itself data-bound.
     * In that case the special key {@code $class} is used to specify the {@link Class#getName};
     * or it may be omitted if the argument is declared to take a concrete type;
     * or {@link Class#getSimpleName} may be used in case the argument type is {@link Describable}
     * and only one subtype is registered (as a {@link Descriptor}) with that simple name.
     */
    public static <T> T instantiate(Class<? extends T> clazz, Map<String,?> arguments) throws Exception {
        ClassDescriptor d = new ClassDescriptor(clazz);
        String[] names = d.loadConstructorParamNames();
        Constructor<T> c = findConstructor(clazz, names.length);
        Object[] args = buildArguments(clazz, arguments, c.getParameterTypes(), names, true);
        T o = c.newInstance(args);
        injectSetters(o, arguments);
        return o;
    }

    /**
     * Computes arguments suitable to pass to {@link #instantiate} to reconstruct this object.
     * @param o a data-bound object
     * @return constructor and/or setter parameters
     * @throws UnsupportedOperationException if the class does not follow the expected structure
     */
    public static Map<String,Object> uninstantiate(Object o) throws UnsupportedOperationException {
        Class<?> clazz = o.getClass();
        Map<String, Object> r = new TreeMap<String, Object>();
        ClassDescriptor d = new ClassDescriptor(clazz);
        String[] names;
        try {
            names = d.loadConstructorParamNames();
        } catch (NoStaplerConstructorException x) {
            throw new UnsupportedOperationException(x);
        }
        for (String name : names) {
            inspect(r, o, clazz, name);
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(DataBoundSetter.class)) {
                    inspect(r, o, clazz, f.getName());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(DataBoundSetter.class) && m.getName().startsWith("set")) {
                    inspect(r, o, clazz, Introspector.decapitalize(m.getName().substring(3)));
                }
            }
        }
        r.values().removeAll(Collections.singleton(null));
        return r;
    }

    private static Object[] buildArguments(Class<?> clazz, Map<String,?> arguments, Class<?>[] types, String[] names, boolean callEvenIfNoArgs) throws Exception {
        Object[] args = new Object[names.length];
        boolean hasArg = callEvenIfNoArgs;
        for (int i = 0; i < args.length; i++) {
            String name = names[i];
            hasArg |= arguments.containsKey(name);
            Object a = arguments.get(name);
            Class<?> type = types[i];
            if (a != null) {
                Object coerced = ReflectionCache.getCachedClass(type).coerceArgument(a);
                if (!Primitives.wrap(type).isInstance(coerced)) {
                    Object bound = tryToBind(type, coerced);
                    if (bound != null) {
                        coerced = bound;
                    } else {
                        throw new ClassCastException(clazz.getName() + "." + name + " expects " + type.getName() + " but received " + coerced.getClass().getName());
                    }
                }
                args[i] = coerced;
            } else if (type == boolean.class) {
                args[i] = false;
            } else if (type.isPrimitive() && callEvenIfNoArgs) {
                throw new UnsupportedOperationException("not yet handling @DataBoundConstructor default value of " + type + "; pass an explicit value for " + name);
            } else {
                // TODO this might be fine (ExecutorStep.label), or not (GenericSCMStep.scm); should inspect parameter annotations for @Nonnull and throw an UOE if found
            }
        }
        return hasArg ? args : null;
    }

    private static @CheckForNull Object tryToBind(Class<?> type, Object o) throws Exception {
        if (o instanceof Map) {
            Map<String,Object> m = new HashMap<String,Object>();
            for (Map.Entry<?,?> entry : ((Map<?,?>) o).entrySet()) {
                m.put((String) entry.getKey(), entry.getValue());
            }

            String clazzS = (String) m.remove("$class");
            Class<?> clazz;
            if (clazzS == null) {
                if (Modifier.isAbstract(type.getModifiers())) {
                    throw new UnsupportedOperationException("must specify $class with an implementation of " + type.getName());
                }
                clazz = type;
            } else if (clazzS.contains(".")) {
                Jenkins j = Jenkins.getInstance();
                ClassLoader loader = j != null ? j.getPluginManager().uberClassLoader : DescribableHelper.class.getClassLoader();
                clazz = loader.loadClass(clazzS);
            } else {
                clazz = null;
                for (Class<?> c : findSubtypes(type)) {
                    if (c.getSimpleName().equals(clazzS)) {
                        if (clazz != null) {
                            throw new UnsupportedOperationException(clazzS + " as a " + type.getName() +  " could mean either " + clazz.getName() + " or " + c.getName());
                        }
                        clazz = c;
                    }
                }
                if (clazz == null) {
                    throw new UnsupportedOperationException("no known implementation of " + type.getName() + " is named " + clazzS);
                }
            }
            return instantiate(clazz.asSubclass(type), m);
        }
        return null;
    }

    // copied from RequestImpl
    private static <T> Constructor<T> findConstructor(Class<? extends T> clazz, int length) {
        @SuppressWarnings("unchecked") Constructor<T>[] ctrs = (Constructor<T>[]) clazz.getConstructors();
        for (Constructor<T> c : ctrs) {
            if (c.getAnnotation(DataBoundConstructor.class) != null) {
                if (c.getParameterTypes().length != length) {
                    throw new IllegalArgumentException(c + " has @DataBoundConstructor but it doesn't match with your .stapler file. Try clean rebuild");
                }
                return c;
            }
        }
        for (Constructor<T> c : ctrs) {
            if (c.getParameterTypes().length == length) {
                return c;
            }
        }
        throw new IllegalArgumentException(clazz + " does not have a constructor with " + length + " arguments");
    }

    /**
     * Injects via {@link DataBoundSetter}
     */
    private static void injectSetters(Object o, Map<String,?> arguments) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(DataBoundSetter.class)) {
                    f.setAccessible(true);
                    if (arguments.containsKey(f.getName())) {
                        Object v = arguments.get(f.getName());
                        f.set(o, v);
                    }
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(DataBoundSetter.class)) {
                    Class<?>[] parameterTypes = m.getParameterTypes();
                    if (!m.getName().startsWith("set") || parameterTypes.length != 1) {
                        throw new IllegalStateException(m + " cannot be a @DataBoundSetter");
                    }
                    m.setAccessible(true);
                    Object[] args = buildArguments(c, arguments, parameterTypes, new String[] {Introspector.decapitalize(m.getName().substring(3))}, false);
                    if (args != null) {
                        m.invoke(o, args);
                    }
                }
            }
        }
    }

    private static void inspect(Map<String, Object> r, Object o, Class<?> clazz, String field) {
        AtomicReference<Class<?>> type = new AtomicReference<Class<?>>();
        Object value = inspect(o, clazz, field, type);
        if (value != null && !value.getClass().getPackage().getName().startsWith("java.")) {
            try {
                // Check to see if this can be treated as a data-bound struct.
                Map<String,Object> nested = uninstantiate(value);
                if (type.get() != value.getClass()) {
                    nested.put("$class", value.getClass().getSimpleName());
                }
                value = nested;
            } catch (UnsupportedOperationException x) {
                // then leave it raw
            }
        }
        r.put(field, value);
    }

    private static Object inspect(Object o, Class<?> clazz, String field, AtomicReference<Class<?>> type) {
        try {
            try {
                Field f = clazz.getField(field);
                type.set(f.getType());
                return f.get(o);
            } catch (NoSuchFieldException x) {
                // OK, check for getter instead
            }
            try {
                Method m = clazz.getMethod("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
                type.set(m.getReturnType());
                return m.invoke(o);
            } catch (NoSuchMethodException x) {
                // one more check
            }
            try {
                type.set(boolean.class);
                return clazz.getMethod("is" + Character.toUpperCase(field.charAt(0)) + field.substring(1)).invoke(o);
            } catch (NoSuchMethodException x) {
                throw new UnsupportedOperationException("no public field ‘" + field + "’ (or getter method) found in " + clazz);
            }
        } catch (UnsupportedOperationException x) {
            throw x;
        } catch (Exception x) {
            throw new UnsupportedOperationException(x);
        }
    }

    static <T> Set<Class<? extends T>> findSubtypes(Class<T> supertype) {
        Set<Class<? extends T>> clazzes = new HashSet<Class<? extends T>>();
        for (Descriptor<?> d : getDescriptorList()) {
            if (supertype.isAssignableFrom(d.clazz)) {
                clazzes.add(d.clazz.asSubclass(supertype));
            }
        }
        return clazzes;
    }

    @SuppressWarnings("rawtypes")
    private static List<? extends Descriptor> getDescriptorList() {
        Jenkins j = Jenkins.getInstance();
        if (j != null) {
            // Jenkins.getDescriptorList does not work well since it is limited to descriptors declaring one supertype, and does not work at all for SimpleBuildStep.
            return j.getExtensionList(Descriptor.class);
        } else {
            // TODO should be part of ExtensionList.lookup in core, but here now for benefit of tests:
            List<Descriptor<?>> descriptors = new ArrayList<Descriptor<?>>();
            for (IndexItem<Extension,Object> item : Index.load(Extension.class, Object.class)) {
                try {
                    Object o = item.instance();
                    if (o instanceof Descriptor) {
                        descriptors.add((Descriptor) o);
                    }
                } catch (InstantiationException x) {
                    // ignore for now
                }
            }
            return descriptors;
        }
    }

    private DescribableHelper() {}

}
