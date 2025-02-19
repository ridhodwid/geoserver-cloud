/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.config.factory;

import static org.springframework.util.StringUtils.hasText;
import static org.springframework.util.StringUtils.tokenizeToStringArray;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Spring xml bean definition reader that uses a regular expression to include or exclude beans by
 * name and alias using a regular expression.
 *
 * <p>It overloads the {@link ImportResource @ImportResource} {@code locations} attribute allowing
 * to append an <b>inclusion</b> filter in the form {@code #name=<regex>}, for example {@code
 * servlet-context.xml#name=<regex>}. That is, if the regular expression applied to the bean name
 * matches, then the bean will be registered, otherwise it'll be discarded.
 *
 * <p>Note, as a compromis, the regular expressions are evaluated against bean names, not aliases.
 * Alias registration is deferred to after all beans matching the regular expression are registered,
 * discarding the alias of beans that were not registered.
 *
 * <p>Examples:
 *
 * <p>Load all beans from a specific xml file on a specific jar file, except those named {@code foo}
 * or {@code bar}:
 *
 * <pre>
 * <code>
 *  &#64;ImportResource(
 *  reader = FilteringXmlBeanDefinitionReader.class,
 *  // exclude beans named foo and bar:
 *  locations = "jar:gs-main-.*!/applicationContext.xml#name=^(?!foo|bar).*$"
 *  )
 * </code>
 * </pre>
 *
 * <p>Load only {@code foo}, {@code bar}, and any bean called "gml*OutputFormat" {@code
 * gml.*OutputFormat}
 *
 * <pre>
 * <code>
 *  &#64;ImportResource(
 *  reader = FilteringXmlBeanDefinitionReader.class,
 *  // exclude beans named foo and bar:
 *  locations = "jar:gs-main-.*!/applicationContext.xml#name=^(foo|bar|gml.*OutputFormat).*$"
 *  )
 * </code>
 * </pre>
 */
@Slf4j(topic = "org.geoserver.cloud.config.factory")
public class FilteringXmlBeanDefinitionReader extends XmlBeanDefinitionReader {

    public FilteringXmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
        super(registry);
    }

    public @Override int loadBeanDefinitions(
            String location, @Nullable Set<Resource> actualResources)
            throws BeanDefinitionStoreException {

        super.setDocumentReaderClass(FilteringBeanDefinitionDocumentReader.class);
        parseAndSetBeanInclusionFilters(location);
        location = removeBeanFilterExpressions(location);
        try {
            return loadBeanDefinitionsApplyingFilters(location, actualResources);
        } finally {
            FilteringBeanDefinitionDocumentReader.releaseFiltersThreadLocals();
        }
    }

    private int loadBeanDefinitionsApplyingFilters(String location, Set<Resource> actualResources) {
        final ResourceLoader resourceLoader = getResourceLoader();
        final boolean filterByResourceLocation =
                (resourceLoader instanceof ResourcePatternResolver)
                        && (location.startsWith("jar:") || location.startsWith("!jar:"));
        if (!filterByResourceLocation) {
            return super.loadBeanDefinitions(location, actualResources);
        }

        // Resource pattern matching available.
        try {
            ResourcePatternResolver patternResolver = (ResourcePatternResolver) resourceLoader;
            final boolean excludeJar = location.startsWith("!");
            if (excludeJar) {
                location = location.substring(1);
            }
            // .*/gs-wfs-.*\.jar\!/
            final String jarName = location.substring("jar:".length(), location.indexOf("!"));
            String jarNameExpression = ".*/" + jarName;
            if (!jarName.endsWith(".jar")) {
                jarNameExpression += "\\.jar";
            }
            jarNameExpression += "\\!/";

            Pattern jarNamePattern = Pattern.compile(jarNameExpression);
            // the resource to load, e.g. applicationContext.xml
            String resourcePattern = location.substring(2 + location.indexOf("!/"));
            Resource[] allClasspathBaseResources = patternResolver.getResources("classpath*:");
            int count = 0;
            for (Resource root : allClasspathBaseResources) {
                String uri = root.getURI().toString();
                if (jarNamePattern.matcher(uri).matches()) {
                    String resourceURI = root.getURI().toString() + resourcePattern;
                    log.debug(
                            "Loading bean definitions from {}, matches pattern {}",
                            resourceURI,
                            jarNameExpression);
                    try {
                        int c = super.loadBeanDefinitions(resourceURI, actualResources);
                        log.debug("Loaded {} bean definitions from {}", c, uri);
                        if (actualResources != null) {
                            actualResources.add(root);
                        }
                        count += c;
                    } catch (BeanDefinitionStoreException fnf) {
                        if (fnf.getCause() instanceof FileNotFoundException) {
                            log.debug("No {} in {}, skipping.", resourcePattern, uri);
                        } else {
                            throw fnf;
                        }
                    }
                }
            }
            return count;
        } catch (IOException ex) {
            throw new BeanDefinitionStoreException(
                    "Could not resolve bean definition resource pattern [" + location + "]", ex);
        }
    }

    private String removeBeanFilterExpressions(String location) {
        if (location.contains(".xml#")) {
            location = location.substring(0, location.indexOf(".xml#") + ".xml#".length() - 1);
        }
        return location;
    }

    private void parseAndSetBeanInclusionFilters(String location) {
        if (location.contains(".xml#")) {
            String filterTypeAndRegularExpression =
                    location.substring(".xml#".length() + location.indexOf(".xml#"));
            if (hasText(filterTypeAndRegularExpression)) {
                String[] split = filterTypeAndRegularExpression.split("=");
                if (split.length != 2) {
                    throw throwInvalidExpression(location, filterTypeAndRegularExpression, null);
                }
                String filterType = split[0];
                if (!"name".equals(filterType)) {
                    throw throwInvalidExpression(location, filterTypeAndRegularExpression, null);
                }
                String regex = split[1];
                addBeanNameIncludeFilter(regex, location);
            }
        }
    }

    private void addBeanNameIncludeFilter(final String regex, final String resourceLocation) {
        try {
            Predicate<String> matcher = Pattern.compile(regex).asMatchPredicate();
            FilteringBeanDefinitionDocumentReader.addMatcher(matcher);
        } catch (RuntimeException e) {
            throw throwInvalidExpression(resourceLocation, regex, e);
        }
    }

    private IllegalArgumentException throwInvalidExpression(
            final String resourceLocation, String regex, Throwable cause) {
        String msg =
                String.format(
                        "Invalid bean filter expression (%s), expected name=<regex>>, resource: %s",
                        regex, resourceLocation);
        throw new IllegalArgumentException(msg);
    }

    public static class FilteringBeanDefinitionDocumentReader
            extends DefaultBeanDefinitionDocumentReader {

        private static ThreadLocal<List<Predicate<String>>> MATCHERS =
                ThreadLocal.withInitial(ArrayList::new);

        public static void addMatcher(Predicate<String> beanDefFilter) {
            MATCHERS.get().add(beanDefFilter);
        }

        public static void releaseFiltersThreadLocals() {
            MATCHERS.remove();
        }

        /**
         * @return {@code true) if any of the filters apply to the bean definition, meaning the bean
         *         shall be registered; {@code false} if the bean shall be discarded
         */
        private boolean include(String name) {
            List<Predicate<String>> matchers = MATCHERS.get();
            for (Predicate<String> matcher : matchers) {
                if (matcher.test(name)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isFiltering() {
            return !MATCHERS.get().isEmpty();
        }

        private Set<String> blackListedBeanNames = new HashSet<String>();
        private Map<String, String> deferredNameToAlias = new HashMap<String, String>();

        protected @Override void doRegisterBeanDefinitions(Element root) {
            super.doRegisterBeanDefinitions(root);
            blackListedBeanNames.forEach(deferredNameToAlias::remove);
            for (Entry<String, String> e : deferredNameToAlias.entrySet()) {
                String name = e.getKey();
                String alias = e.getValue();
                registerDeferredAlias(name, alias);
            }
        }

        private void registerDeferredAlias(String name, String alias) {
            String msgFormat = "Registering   '{}' alias for '{}'";
            log.trace(msgFormat, alias, name);
            try {
                getReaderContext().getRegistry().registerAlias(name, alias);
            } catch (Exception ex) {
                getReaderContext()
                        .error(
                                "Failed to register alias '"
                                        + alias
                                        + "' for bean with name '"
                                        + name
                                        + "'",
                                null,
                                ex);
            }
            getReaderContext().fireAliasRegistered(name, alias, null);
        }

        protected @Override void processAliasRegistration(Element ele) {
            if (!isFiltering()) {
                super.processAliasRegistration(ele);
                return;
            }
            final String name = ele.getAttribute(NAME_ATTRIBUTE);
            final String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
            if (!StringUtils.hasText(name)) {
                getReaderContext().error("Name must not be empty", ele);
                return;
            }
            if (!StringUtils.hasText(alias)) {
                getReaderContext().error("Alias must not be empty", ele);
                return;
            }
            deferredNameToAlias.put(name, alias);
        }

        protected @Override void processBeanDefinition(
                Element ele, BeanDefinitionParserDelegate delegate) {
            if (!isFiltering()) {
                super.processBeanDefinition(ele, delegate);
                return;
            }

            final String beanNameOrId = getBeanNameOrId(ele);
            if (shallInclude(beanNameOrId, ele)) {
                if (hasText(beanNameOrId)) {
                    logIncludingBeanMessage(beanNameOrId);
                }
                super.processBeanDefinition(ele, delegate);
            } else if (hasText(beanNameOrId)) {
                blackListedBeanNames.add(beanNameOrId);
                logExcludedBeanMessage(beanNameOrId);
            }
        }

        private boolean shallInclude(String nameAtt, Element ele) {
            if (!hasText(nameAtt) || include(nameAtt)) {
                return true;
            }

            String aliasAtt = ele.getAttribute(ALIAS_ATTRIBUTE);
            if (!hasText(aliasAtt)) aliasAtt = nameAtt; // name can be a comma separated list

            String[] aliases =
                    tokenizeToStringArray(
                            nameAtt, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
            for (String alias : aliases) {
                if (include(alias)) {
                    return true;
                }
            }
            return false;
        }

        private String getBeanNameOrId(Element ele) {
            String nameAtt = ele.getAttribute(NAME_ATTRIBUTE);
            if (!hasText(nameAtt)) {
                nameAtt = ele.getAttribute("id"); // old style
            }
            return nameAtt;
        }

        private void logIncludingBeanMessage(String beanName) {
            String msgFormat = "Registering   '{}', matches regular expression";
            log.trace(msgFormat, beanName);
        }

        private void logExcludedBeanMessage(String beanName) {
            String msgFormat = "Excluded bean '{}', no regular expression matches";
            log.trace(msgFormat, beanName);
        }
    }
}
