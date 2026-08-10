package com.codepilot1c.core.edt.lang;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;

/**
 * Answers which kind of module a BSL file is.
 *
 * <p>{@link Module#getModuleType()} cannot answer that on its own. {@code moduleType} is a plain
 * stored EMF attribute whose default is {@link ModuleType#COMMON_MODULE}, and the only thing that
 * ever assigns it is EDT's own derived-state computer — which does not run for a module this bundle
 * obtains straight from the parser. Reading the field therefore reported <em>every</em> module kind
 * as {@code COMMON_MODULE}, including object, manager and form modules
 * (issues/2026-08-10-bsl-module-context-moduletype-always-common-module.md).
 *
 * <p>The kind is instead derived the way EDT derives it — {@link BslUtil#computeModuleType} keyed on
 * the module's qualified name — so the literals reported here are EDT's own and cannot drift away
 * from them. When the derivation yields nothing, the stored value is reported only where it can
 * still be true: a stored {@code COMMON_MODULE} for a file that does not live under
 * {@code CommonModules/} is the untouched default rather than an answer, and is reported as unknown
 * ({@code null}) instead of as a kind the module demonstrably does not have.
 */
public final class BslModuleTypeResolver {

    private static final String COMMON_MODULE_LITERAL = "COMMON_MODULE"; //$NON-NLS-1$
    private static final String COMMON_MODULES_FOLDER = "CommonModules"; //$NON-NLS-1$

    private BslModuleTypeResolver() {
        // utility
    }

    /**
     * Resolves the module kind for a parsed module, deriving it from the module's qualified name and
     * falling back to whatever the model stores.
     *
     * @param module the parsed module, may be {@code null}
     * @param resource the resource the module was parsed from, used to reach EDT's path-to-FQN
     *            converter; may be {@code null}
     * @param filePath the module path as the caller spelled it, used to tell a stored
     *            {@code COMMON_MODULE} apart from the untouched default; may be {@code null}
     * @return the {@link ModuleType} literal, or {@code null} when the kind is unknown
     */
    public static String resolve(Module module, XtextResource resource, String filePath) {
        return resolve(module, findConverter(resource), filePath);
    }

    static String resolve(Module module, IQualifiedNameFilePathConverter converter, String filePath) {
        if (module == null) {
            return null;
        }
        String derived = derive(module, converter);
        if (derived != null) {
            return derived;
        }
        return storedFallback(literalOf(module.getModuleType()), filePath);
    }

    /**
     * Returns the literal EDT would compute for the module, or {@code null} when EDT cannot map its
     * qualified name to a kind (too few segments, an unknown module file name, or no converter to
     * turn the module's path into an FQN in the first place).
     */
    private static String derive(Module module, IQualifiedNameFilePathConverter converter) {
        if (converter == null) {
            return null;
        }
        ModuleType derived;
        try {
            derived = BslUtil.computeModuleType(module, converter);
        } catch (RuntimeException | LinkageError | AssertionError e) {
            // getFqn() asserts on paths it does not recognise; an unmappable path is not an error
            // here, it just means the kind stays unknown.
            return null;
        }
        if (derived == null) {
            return null;
        }
        String literal = literalOf(derived);
        // computeModuleType() answers COMMON_MODULE for a module whose URI is not a platform one —
        // the same value the untouched default carries, so it is no more of an answer than the
        // field was. Let the caller's path decide whether it can be true.
        if (COMMON_MODULE_LITERAL.equals(literal) && !isPlatformResource(module)) {
            return null;
        }
        return literal;
    }

    /**
     * Reports the stored literal unless the file path contradicts it: only a module under
     * {@code CommonModules/} can be a common module, so any other path carrying the default
     * {@code COMMON_MODULE} is an unset field rather than a kind.
     */
    static String storedFallback(String storedLiteral, String filePath) {
        if (storedLiteral == null) {
            return null;
        }
        if (COMMON_MODULE_LITERAL.equals(storedLiteral) && !mayBeCommonModulePath(filePath)) {
            return null;
        }
        return storedLiteral;
    }

    static boolean mayBeCommonModulePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            // Nothing to contradict the stored value with.
            return true;
        }
        String normalized = filePath.replace('\\', '/');
        return normalized.startsWith(COMMON_MODULES_FOLDER + '/')
                || normalized.contains('/' + COMMON_MODULES_FOLDER + '/');
    }

    private static boolean isPlatformResource(EObject module) {
        return module.eResource() != null
                && module.eResource().getURI() != null
                && module.eResource().getURI().isPlatform();
    }

    private static String literalOf(ModuleType moduleType) {
        if (moduleType == null) {
            return null;
        }
        String literal = trimToNull(moduleType.getLiteral());
        return literal != null ? literal : trimToNull(moduleType.getName());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Finds EDT's {@link IQualifiedNameFilePathConverter}: first in the BSL language injector behind
     * the resource (where EDT's own derived-state computer gets it), then as an OSGi service (EDT
     * binds the interface {@code toService()}). Both lookups are non-blocking — a missing converter
     * degrades to an unknown kind, it must never stall a tool call.
     */
    private static IQualifiedNameFilePathConverter findConverter(XtextResource resource) {
        IQualifiedNameFilePathConverter fromLanguage = fromLanguageInjector(resource);
        return fromLanguage != null ? fromLanguage : fromOsgiService();
    }

    private static IQualifiedNameFilePathConverter fromLanguageInjector(XtextResource resource) {
        if (resource == null) {
            return null;
        }
        try {
            IResourceServiceProvider provider = resource.getResourceServiceProvider();
            if (provider == null) {
                return null;
            }
            return provider.get(IQualifiedNameFilePathConverter.class);
        } catch (RuntimeException | LinkageError e) {
            // The binding is declared optional in EDT's own qualified-name provider, so an injector
            // without it throws rather than returning null.
            return null;
        }
    }

    private static IQualifiedNameFilePathConverter fromOsgiService() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(BslModuleTypeResolver.class);
            if (bundle == null) {
                return null;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null) {
                return null;
            }
            ServiceReference<IQualifiedNameFilePathConverter> reference =
                    context.getServiceReference(IQualifiedNameFilePathConverter.class);
            if (reference == null) {
                return null;
            }
            return context.getService(reference);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
