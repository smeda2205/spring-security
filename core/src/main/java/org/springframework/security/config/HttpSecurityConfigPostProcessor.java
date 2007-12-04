package org.springframework.security.config;

import org.springframework.security.concurrent.ConcurrentSessionFilter;
import org.springframework.security.context.HttpSessionContextIntegrationFilter;
import org.springframework.security.ui.AbstractProcessingFilter;
import org.springframework.security.ui.AuthenticationEntryPoint;
import org.springframework.security.ui.rememberme.RememberMeServices;
import org.springframework.security.util.FilterChainProxy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.servlet.Filter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Responsible for tying up the HTTP security configuration - building ordered filter stack and linking up
 * with other beans.
 *
 * @author Luke Taylor
 * @version $Id$
 */
public class HttpSecurityConfigPostProcessor implements BeanFactoryPostProcessor, Ordered {
    private Log logger = LogFactory.getLog(getClass());

    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        ConfigUtils.registerAccessManagerIfNecessary(beanFactory);
        BeanDefinition securityInterceptor =
                beanFactory.getBeanDefinition(BeanIds.FILTER_SECURITY_INTERCEPTOR);

        ConfigUtils.configureSecurityInterceptor(beanFactory, securityInterceptor);

        configureRememberMeSerices(beanFactory);

        configureAuthenticationEntryPoint(beanFactory);

        configureAuthenticationFilter(beanFactory);

        configureFilterChain(beanFactory);
    }

    private void configureRememberMeSerices(ConfigurableListableBeanFactory beanFactory) {
        try {
            BeanDefinition rememberMeServices =
                    beanFactory.getBeanDefinition(BeanIds.REMEMBER_ME_SERVICES);
            rememberMeServices.getPropertyValues().addPropertyValue("userDetailsService",
                    ConfigUtils.getUserDetailsService(beanFactory));
        } catch (NoSuchBeanDefinitionException e) {
            // ignore
        }
    }

    /**
     * Sets the authentication manager, (and remember-me services, if required) on any instances of
     * AbstractProcessingFilter
     */
    private void configureAuthenticationFilter(ConfigurableListableBeanFactory beanFactory) {
        Map beans = beanFactory.getBeansOfType(RememberMeServices.class);

        RememberMeServices rememberMeServices = null;

        if (beans.size() > 0) {
            rememberMeServices = (RememberMeServices) beans.values().toArray()[0];
        }

        Iterator authFilters = beanFactory.getBeansOfType(AbstractProcessingFilter.class).values().iterator();

        while (authFilters.hasNext()) {
            AbstractProcessingFilter filter = (AbstractProcessingFilter) authFilters.next();

            if (rememberMeServices != null) {
                logger.info("Using RememberMeServices " + rememberMeServices + " with filter " + filter);
                filter.setRememberMeServices(rememberMeServices);
            }
        }
    }

    /**
     * Selects the entry point that should be used in ExceptionTranslationFilter. Strategy is
     *
     * <ol>
     * <li>If only one use that.</li>
     * <li>If more than one, check the default interactive login Ids in order of preference</li>
     * <li>throw an exception (for now). TODO: Examine additional beans and types and make decision</li>
     * </ol>
     *
     */
    private void configureAuthenticationEntryPoint(ConfigurableListableBeanFactory beanFactory) {
        logger.info("Selecting AuthenticationEntryPoint for use in ExceptionTranslationFilter");

        BeanDefinition etf =
                beanFactory.getBeanDefinition(BeanIds.EXCEPTION_TRANSLATION_FILTER);
        Map entryPointMap = beanFactory.getBeansOfType(AuthenticationEntryPoint.class);
        List entryPoints = new ArrayList(entryPointMap.values());

        Assert.isTrue(entryPoints.size() > 0, "No AuthenticationEntryPoint instances defined");

        AuthenticationEntryPoint mainEntryPoint = (AuthenticationEntryPoint)
                entryPointMap.get(BeanIds.FORM_LOGIN_ENTRY_POINT);

        if (mainEntryPoint == null) {
            throw new SecurityConfigurationException("Failed to resolve authentication entry point");
        }

        logger.info("Main AuthenticationEntryPoint set to " + mainEntryPoint);

        etf.getPropertyValues().addPropertyValue("authenticationEntryPoint", mainEntryPoint);
    }

    private void configureFilterChain(ConfigurableListableBeanFactory beanFactory) {
        FilterChainProxy filterChainProxy =
                (FilterChainProxy) beanFactory.getBean(BeanIds.FILTER_CHAIN_PROXY);
        // Set the default match
        List defaultFilterChain = orderFilters(beanFactory);

        // Note that this returns a copy
        Map filterMap = filterChainProxy.getFilterChainMap();

        String allUrlsMatch = filterChainProxy.getMatcher().getUniversalMatchPattern();

        filterMap.put(allUrlsMatch, defaultFilterChain);

        filterChainProxy.setFilterChainMap(filterMap);

        Map sessionFilters = beanFactory.getBeansOfType(ConcurrentSessionFilter.class);

        if (!sessionFilters.isEmpty()) {
            logger.info("Concurrent session filter in use, setting 'forceEagerSessionCreation' to true");
            HttpSessionContextIntegrationFilter scif = (HttpSessionContextIntegrationFilter)
                    beanFactory.getBean(BeanIds.HTTP_SESSION_CONTEXT_INTEGRATION_FILTER);
            scif.setForceEagerSessionCreation(true);
        }
    }

    private List orderFilters(ConfigurableListableBeanFactory beanFactory) {
        Map filters = beanFactory.getBeansOfType(Filter.class);

        Assert.notEmpty(filters, "No filters found in app context!");

        Iterator ids = filters.keySet().iterator();

        List orderedFilters = new ArrayList();

        while (ids.hasNext()) {
            String id = (String) ids.next();
            Filter filter = (Filter) filters.get(id);

            if (filter instanceof FilterChainProxy) {
                continue;
            }

            if (!(filter instanceof Ordered)) {
                // TODO: Possibly log this as a warning and skip this filter.
                throw new SecurityConfigurationException("Filter " + id + " must implement the Ordered interface");
            }

            orderedFilters.add(filter);
        }

        Collections.sort(orderedFilters, new OrderComparator());

        return orderedFilters;
    }

    private Object getBeanOfType(Class clazz, ConfigurableListableBeanFactory beanFactory) {
        Map beans = beanFactory.getBeansOfType(clazz);

        Assert.isTrue(beans.size() == 1, "Required a single bean of type " + clazz + " but found " + beans.size());

        return beans.values().toArray()[0];
    }

    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}