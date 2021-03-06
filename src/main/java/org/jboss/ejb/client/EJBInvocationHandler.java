/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import javax.ejb.EJBHome;
import javax.ejb.EJBObject;

/**
 * @param <T> the proxy view type
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@SuppressWarnings({"SerializableClassWithUnconstructableAncestor"})
final class EJBInvocationHandler<T> extends Attachable implements InvocationHandler, Serializable {

    private static final long serialVersionUID = 946555285095057230L;

    private final transient boolean async;
    private final EJBLocator<T> locator;

    /**
     * map of methods that can be handled on the client side
     */
    private static final Map<MethodKey, MethodHandler> clientSideMethods;

    static {
        Map<MethodKey, MethodHandler> methods = new HashMap<MethodKey, MethodHandler>();
        methods.put(new MethodKey("equals", Object.class), new EqualsMethodHandler());
        methods.put(new MethodKey("hashCode"), new HashCodeMethodHandler());
        methods.put(new MethodKey("toString"), new ToStringMethodHandler());
        methods.put(new MethodKey("getPrimaryKey"), new GetPrimaryKeyHandler());
        methods.put(new MethodKey("getHandle"), new GetHandleHandler());
        methods.put(new MethodKey("isIdentical", EJBObject.class), new IsIdenticalHandler());
        methods.put(new MethodKey("getHomeHandle"), new GetHomeHandleHandler());
        clientSideMethods = Collections.unmodifiableMap(methods);
    }

    EJBInvocationHandler(final EJBLocator<T> locator) {
        if (locator == null) {
            throw new NullPointerException("locator is null");
        }
        this.locator = locator;
        async = false;
    }

    EJBInvocationHandler(final EJBInvocationHandler<T> other) {
        super(other);
        locator = other.locator;
        async = true;
    }

    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        return doInvoke(locator.getViewType().cast(proxy), method, args);
    }

    Object doInvoke(final T proxy, final Method method, final Object[] args) throws Throwable {
        final MethodHandler handler = clientSideMethods.get(new MethodKey(method));
        if (handler != null && handler.canHandleInvocation(this, proxy, method, args)) {
            return handler.invoke(this, proxy, method, args);
        }
        final EJBClientContext context = EJBClientContext.requireCurrent();
        final EJBReceiver<?> receiver = context.requireEJBReceiver(locator.getAppName(), locator.getModuleName(), locator.getDistinctName());
        return doInvoke(this, async, proxy, method, args, receiver, context);
    }

    @SuppressWarnings("unchecked")
    static <T> EJBInvocationHandler<? extends T> forProxy(T proxy) {
        InvocationHandler handler = Proxy.getInvocationHandler(proxy);
        if (handler instanceof EJBInvocationHandler) {
            return (EJBInvocationHandler<? extends T>) handler;
        }
        throw Logs.MAIN.proxyNotOurs(proxy, EJBClient.class.getName());
    }

    private static <T, A> Object doInvoke(final EJBInvocationHandler<T> ejbInvocationHandler, final boolean async, final T proxy, final Method method, final Object[] args, final EJBReceiver<A> receiver, EJBClientContext clientContext) throws Throwable {
        // todo - concatenate receiver chain too
        final EJBReceiverContext ejbReceiverContext = clientContext.requireEJBReceiverContext(receiver);
        final EJBClientInvocationContext<A> invocationContext = new EJBClientInvocationContext<A>(ejbInvocationHandler, clientContext, receiver.createReceiverSpecific(), receiver, ejbReceiverContext, proxy, method, args);

        invocationContext.sendRequest();

        if (!async) {
            // wait for invocation to complete
            final Object value = invocationContext.awaitResponse();
            if (value != EJBClientInvocationContext.PROCEED_ASYNC) {
                return value;
            }
            // proceed asynchronously
        }
        // force async...
        if (method.getReturnType() == Future.class) {
            return invocationContext.getFutureResponse();
        } else if (method.getReturnType() == void.class) {
            invocationContext.setDiscardResult();
            // Void return
            return null;
        } else {
            // wrap return always
            EJBClient.setFutureResult(invocationContext.getFutureResponse());
            return null;
        }
    }

    protected Object writeReplace() {
        return new SerializedEJBInvocationHandler(locator);
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        if (async) throw new NotSerializableException("Async proxies are not serializable");
        oos.defaultWriteObject();
    }

    EJBInvocationHandler<T> getAsyncHandler() {
        return async ? this : new EJBInvocationHandler<T>(this);
    }

    EJBLocator<T> getLocator() {
        return locator;
    }

    private static final class MethodKey {

        private final String name;
        private final Class[] parameters;


        public MethodKey(final String name, final Class... parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        public MethodKey(final Method method) {
            this.name = method.getName();
            this.parameters = method.getParameterTypes();
        }

        @Override
        public boolean equals(final Object o) {
            //we don't care about the return type
            //we hant co-cariant methods to still be handled
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final MethodKey methodKey = (MethodKey) o;

            if (!name.equals(methodKey.name)) return false;
            if (!Arrays.equals(parameters, methodKey.parameters)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + Arrays.hashCode(parameters);
            return result;
        }
    }

    private static interface MethodHandler {

        boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception;

        Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception;
    }

    private static final class EqualsMethodHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            return true;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            final Object other = args[0];
            if (other instanceof Proxy) {
                final InvocationHandler handler = Proxy.getInvocationHandler(other);
                if (handler instanceof EJBInvocationHandler) {
                    return thisHandler.locator.equals(((EJBInvocationHandler<?>) handler).locator);
                }
            }
            return Boolean.FALSE;
        }
    }


    private static final class HashCodeMethodHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            return true;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            return thisHandler.locator.hashCode();
        }
    }

    private static final class ToStringMethodHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            return true;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) {
            return String.format("Proxy for remote EJB %s", thisHandler.locator);
        }
    }

    private static final class GetPrimaryKeyHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            return proxy instanceof EJBObject;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            final EJBLocator<?> locator = thisHandler.locator;
            if (locator instanceof EntityEJBLocator) {
                return ((EntityEJBLocator) locator).getPrimaryKey();
            }
            throw new RemoteException("Cannot invoke getPrimaryKey() om " + proxy);
        }
    }

    private static final class GetHandleHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            return proxy instanceof EJBObject && thisHandler.locator instanceof EJBLocator;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            final EJBLocator locator = (EJBLocator) thisHandler.getLocator();
            return new EJBHandle(locator);
        }
    }

    private static final class IsIdenticalHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            return proxy instanceof EJBObject && thisHandler.locator instanceof EJBLocator;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            final EJBLocator<?> locator = thisHandler.locator;
            final Object other = args[0];
            if (Proxy.isProxyClass(other.getClass())) {
                final InvocationHandler handler = Proxy.getInvocationHandler(other);
                if (handler instanceof EJBInvocationHandler) {
                    return locator.equals(((EJBInvocationHandler) handler).getLocator());
                }
            }
            return false;
        }
    }

    private static final class GetHomeHandleHandler implements MethodHandler {

        @Override
        public boolean canHandleInvocation(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            return proxy instanceof EJBHome;
        }

        @Override
        public Object invoke(final EJBInvocationHandler thisHandler, final Object proxy, final Method method, final Object[] args) throws Exception {
            final EJBLocator locator = (EJBLocator) thisHandler.getLocator();
            if (locator instanceof EJBHomeLocator) {
                return new EJBHomeHandle((EJBHomeLocator) locator);
            }
            throw new RemoteException("Cannot invoke getHomeHandle() on " + proxy);
        }
    }
}
