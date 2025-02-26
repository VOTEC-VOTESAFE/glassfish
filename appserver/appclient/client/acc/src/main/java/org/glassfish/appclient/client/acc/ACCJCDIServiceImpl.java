/*
 * Copyright (c) 2021 Contributors to Eclipse Foundation.
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.appclient.client.acc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.manager.api.WeldManager;
import org.jvnet.hk2.annotations.Service;

import com.sun.enterprise.container.common.spi.InterceptorInvoker;
import com.sun.enterprise.container.common.spi.JCDIService;
import com.sun.enterprise.container.common.spi.JavaEEInterceptorBuilder;
import com.sun.enterprise.container.common.spi.util.InjectionManager;
import com.sun.enterprise.deployment.BundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.EjbInterceptor;
import com.sun.enterprise.deployment.ManagedBeanDescriptor;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionTarget;
import jakarta.enterprise.inject.spi.InjectionTargetFactory;
import jakarta.inject.Inject;
import jakarta.servlet.ServletContext;

/**
 * @author <a href="mailto:phil.zampino@oracle.com">Phil Zampino</a>
 */
@Service
public class ACCJCDIServiceImpl implements JCDIService {

    private WeldContainer weldContainer;

    @Inject
    private InjectionManager InjectionManager;

    @Override
    public boolean isCurrentModuleJCDIEnabled() {
        return hasBeansXML(Thread.currentThread().getContextClassLoader());
    }

    @Override
    public boolean isJCDIEnabled(BundleDescriptor bundle) {
        return hasBeansXML(bundle.getClassLoader());
    }

    @Override
    public boolean isCDIScoped(Class<?> clazz) {
        throw new UnsupportedOperationException("Application Client Container");
    }

    @Override
    public void setELResolver(ServletContext servletContext) throws NamingException {
        throw new UnsupportedOperationException("Application Client Container");
    }

    @Override
    public JCDIInjectionContext createManagedObject(Class managedClass, BundleDescriptor bundle) {
        return createManagedObject(managedClass, bundle, true);
    }

    private <T> T createEEManagedObject(ManagedBeanDescriptor desc) throws Exception {
        JavaEEInterceptorBuilder interceptorBuilder = (JavaEEInterceptorBuilder) desc.getInterceptorBuilder();

        InterceptorInvoker interceptorInvoker = interceptorBuilder.createInvoker(null);

        Object[] interceptorInstances = interceptorInvoker.getInterceptorInstances();

        // Inject interceptor instances
        for (int i = 0; i < interceptorInstances.length; i++) {
            InjectionManager.injectInstance(interceptorInstances[i], desc.getGlobalJndiName(), false);
        }

        interceptorInvoker.invokeAroundConstruct();

        // This is the managed bean class instance
        T managedBean = (T) interceptorInvoker.getTargetInstance();

        InjectionManager.injectInstance(managedBean, desc);

        interceptorInvoker.invokePostConstruct();

        desc.addBeanInstanceInfo(managedBean, interceptorInvoker);

        return managedBean;
    }

    @Override
    @SuppressWarnings("unchecked")
    public JCDIInjectionContext createManagedObject(Class managedClass, BundleDescriptor bundle, boolean invokePostConstruct) {
        Object managedObject = null;

        try {
            managedObject = createEEManagedObject(bundle.getManagedBeanByBeanClass(managedClass.getName()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        WeldContainer weldContainer = getWeldContainer();
        if (weldContainer == null) {
            return null;
        }

        BeanManager beanManager = weldContainer.getBeanManager();

        CreationalContext<Object> creationalContext = beanManager.createCreationalContext(null);
        AnnotatedType<Object> annotatedType = beanManager.createAnnotatedType(managedClass);
        InjectionTargetFactory<Object> injectionTargetFactory = beanManager.getInjectionTargetFactory(annotatedType);
        InjectionTarget<Object> target = injectionTargetFactory.createInjectionTarget(null);

        target.inject(managedObject, creationalContext);

        if (invokePostConstruct) {
            target.postConstruct(managedObject);
        }

        return new JCDIInjectionContextImpl(target, creationalContext, managedObject);

    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectManagedObject(Object managedObject, BundleDescriptor bundle) {
        WeldContainer weldContainer = getWeldContainer();
        if (weldContainer == null) {
            return;
        }

        BeanManager beanManager = weldContainer.getBeanManager();

        CreationalContext<Object> creationalContext = beanManager.createCreationalContext(null);
        AnnotatedType<Object> annotatedType = (AnnotatedType<Object>) beanManager.createAnnotatedType(managedObject.getClass());
        InjectionTargetFactory<Object> injectionTargetFactory = beanManager.getInjectionTargetFactory(annotatedType);
        InjectionTarget<Object> target = injectionTargetFactory.createInjectionTarget(null);

        target.inject(managedObject, creationalContext);
    }

    @Override
    public <T> T createInterceptorInstance(Class<T> interceptorClass, EjbDescriptor ejbDesc, JCDIService.JCDIInjectionContext ejbContext, Set<EjbInterceptor> ejbInterceptors) {
        T interceptorInstance = null;

        WeldContainer weldContainer = getWeldContainer();
        if (weldContainer != null) {
            BeanManager beanManager = weldContainer.getBeanManager();

            AnnotatedType annotatedType = beanManager.createAnnotatedType(interceptorClass);
            InjectionTarget target = ((WeldManager) beanManager).getInjectionTargetFactory(annotatedType)
                    .createInterceptorInjectionTarget();

            CreationalContext cc = beanManager.createCreationalContext(null);

            interceptorInstance = (T) target.produce(cc);
            target.inject(interceptorInstance, cc);
        }

        return interceptorInstance;
    }

    @Override
    public <T> JCDIInjectionContext<T> createJCDIInjectionContext(EjbDescriptor ejbDesc, Map<Class, Object> ejbInfo) {
        return createJCDIInjectionContext(ejbDesc, null, null);
    }

    @Override
    public <T> JCDIInjectionContext<T> createJCDIInjectionContext(EjbDescriptor ejbDesc, T instance, Map<Class, Object> ejbInfo) {
        throw new UnsupportedOperationException("Application Client Container");
    }

    @Override
    public JCDIInjectionContext createEmptyJCDIInjectionContext() {
        return new JCDIInjectionContextImpl();
    }

    @Override
    public void injectEJBInstance(JCDIInjectionContext injectionCtx) {
        throw new UnsupportedOperationException("Application Client Container");
    }

    private WeldContainer getWeldContainer() {
        if (weldContainer == null) {
            try {
                weldContainer = (new Weld()).initialize();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return weldContainer;
    }

    private boolean hasBeansXML(ClassLoader classLoader) {
        return classLoader.getResource("META-INF/beans.xml") != null;
    }

    private static class JCDIInjectionContextImpl implements JCDIInjectionContext {

        InjectionTarget it;
        CreationalContext cc;
        Object instance;

        JCDIInjectionContextImpl() {
        }

        JCDIInjectionContextImpl(InjectionTarget it, CreationalContext cc, Object i) {
            this.it = it;
            this.cc = cc;
            this.instance = i;
        }

        @Override
        public Object getInstance() {
            return instance;
        }

        @Override
        public void setInstance(Object instance) {
            this.instance = instance;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void cleanup(boolean callPreDestroy) {

            if (callPreDestroy) {
                it.preDestroy(instance);
            }

            it.dispose(instance);
            cc.release();
        }

        @Override
        public InjectionTarget getInjectionTarget() {
            return it;
        }

        @Override
        public void setInjectionTarget(InjectionTarget injectionTarget) {
            this.it = injectionTarget;
        }

        @Override
        public CreationalContext getCreationalContext() {
            return cc;
        }

        @Override
        public void setCreationalContext(CreationalContext creationalContext) {
            this.cc = creationalContext;
        }

        @Override
        public void addDependentContext(JCDIInjectionContext dependentContext) {
            // nothing for now
        }

        @Override
        public Collection<JCDIInjectionContext> getDependentContexts() {
            return new ArrayList<>();
        }

        @Override
        public Object createEjbAfterAroundConstruct() {
            // nothing for now
            return null;
        }
    }

}
