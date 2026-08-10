package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.xtext.naming.QualifiedName;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;

/**
 * Behaviour of {@link BslModuleTypeResolver} — which module kind gets reported.
 *
 * <p>Pinned around the live 2026-08-10 case: {@code bsl_module_context} answered
 * {@code COMMON_MODULE} for the object module, the manager module and a form module of the same
 * document (method counts differed, so the right file was read each time — only the kind was wrong).
 * {@code Module.getModuleType()} is a stored EMF attribute defaulting to {@code COMMON_MODULE} that
 * nothing assigns on a parsed module, so the field was never an answer to begin with.</p>
 *
 * <p>These tests drive EDT's own {@code BslUtil.computeModuleType} through a converter stub, so they
 * pin the real FQN-to-kind mapping rather than a local copy of it — the literals asserted here are
 * the ones EDT emits.</p>
 */
public class BslModuleTypeResolverTest {

    private static final String PLATFORM_PATH = "/Proj/src/Documents/Foo/ObjectModule.bsl"; //$NON-NLS-1$

    @Test
    public void anObjectModuleIsReportedAsAnObjectModule() {
        assertEquals(ModuleType.OBJECT_MODULE.getLiteral(),
                resolveWithFqn("Document", "Foo", "ObjectModule")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aManagerModuleIsReportedAsAManagerModule() {
        assertEquals(ModuleType.MANAGER_MODULE.getLiteral(),
                resolveWithFqn("Catalog", "Bar", "ManagerModule")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aFormModuleIsReportedAsAFormModule() {
        // "Module" as the last segment is ambiguous on its own — EDT disambiguates it by the segment
        // in front of it.
        assertEquals(ModuleType.FORM_MODULE.getLiteral(),
                resolveWithFqn("Document", "Foo", "Form", "Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void aRecordSetModuleUsesEdtsOwnSpelling() {
        // RECORDSET_MODULE, not RECORD_SET_MODULE: the spelling comes from EDT, not from here.
        assertEquals("RECORDSET_MODULE", //$NON-NLS-1$
                resolveWithFqn("InformationRegister", "Reg", "RecordSetModule")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aCommonModuleIsStillReportedAsACommonModule() {
        assertEquals(ModuleType.COMMON_MODULE.getLiteral(),
                resolveWithFqn("CommonModule", "Util", "Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aConfigurationModuleIsReportedFromATwoSegmentName() {
        assertEquals(ModuleType.MANAGED_APP_MODULE.getLiteral(),
                resolveWithFqn("Configuration", "ManagedApplicationModule")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnmappableNameLeavesTheKindUnknown() {
        // A bare "Module" under a document maps to nothing, and the stored default cannot be true
        // for that path — so the answer is "unknown", not "common module".
        assertNull(resolveWithFqn("Document", "Foo", "Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aMissingConverterLeavesTheKindUnknownRatherThanCommon() {
        // This is the whole defect: with nothing to derive from, the stored COMMON_MODULE must not be
        // passed off as the kind of an object module.
        assertNull(BslModuleTypeResolver.resolve(module(), (IQualifiedNameFilePathConverter) null,
                "Documents/Foo/ObjectModule.bsl")); //$NON-NLS-1$
    }

    @Test
    public void aCommonModulePathKeepsTheStoredValueWhenNothingCanBeDerived() {
        assertEquals(ModuleType.COMMON_MODULE.getLiteral(),
                BslModuleTypeResolver.resolve(module(), (IQualifiedNameFilePathConverter) null,
                        "CommonModules/Util/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownPathCannotContradictTheStoredValue() {
        // No path to reason about: report what the model holds rather than dropping the field.
        assertEquals(ModuleType.COMMON_MODULE.getLiteral(),
                BslModuleTypeResolver.resolve(module(), (IQualifiedNameFilePathConverter) null, null));
    }

    @Test
    public void aConverterThatThrowsLeavesTheKindUnknown() {
        Module module = module();
        String resolved = BslModuleTypeResolver.resolve(module, new ThrowingConverter(),
                "Documents/Foo/ObjectModule.bsl"); //$NON-NLS-1$
        assertNull(resolved);
    }

    @Test
    public void aNullModuleIsUnknown() {
        assertNull(BslModuleTypeResolver.resolve(null, (IQualifiedNameFilePathConverter) null,
                "Documents/Foo/ObjectModule.bsl")); //$NON-NLS-1$
    }

    @Test
    public void commonModulePathsAreRecognisedWithAndWithoutTheSourceFolder() {
        assertTrue(BslModuleTypeResolver.mayBeCommonModulePath("CommonModules/Util/Module.bsl")); //$NON-NLS-1$
        assertTrue(BslModuleTypeResolver.mayBeCommonModulePath("src/CommonModules/Util/Module.bsl")); //$NON-NLS-1$
        assertTrue(BslModuleTypeResolver.mayBeCommonModulePath("src\\CommonModules\\Util\\Module.bsl")); //$NON-NLS-1$
        assertFalse(BslModuleTypeResolver.mayBeCommonModulePath("Documents/Foo/ObjectModule.bsl")); //$NON-NLS-1$
        assertFalse(BslModuleTypeResolver.mayBeCommonModulePath("CommonModulesX/Util/Module.bsl")); //$NON-NLS-1$
    }

    /**
     * Resolves through EDT's mapping with a converter that answers the given qualified name for the
     * module's platform path.
     */
    private static String resolveWithFqn(String... segments) {
        return BslModuleTypeResolver.resolve(module(), new StubConverter(QualifiedName.create(segments)),
                "Documents/Foo/ObjectModule.bsl"); //$NON-NLS-1$
    }

    /**
     * A parsed module carries a platform URI — that is what EDT keys the derivation on, and without
     * it {@code computeModuleType} short-circuits to the same default the field already held.
     */
    private static Module module() {
        Module module = BslFactory.eINSTANCE.createModule();
        ResourceImpl resource = new ResourceImpl(URI.createPlatformResourceURI(PLATFORM_PATH, true));
        resource.getContents().add(module);
        return module;
    }

    private static class StubConverter implements IQualifiedNameFilePathConverter {

        private final QualifiedName answer;

        StubConverter(QualifiedName answer) {
            this.answer = answer;
        }

        @Override
        public QualifiedName getFqn(String path) {
            // EDT hands its converter the workspace-relative path of the module, source folder and
            // all; pin that so the plumbing cannot silently change shape.
            assertEquals(PLATFORM_PATH, path);
            return answer;
        }

        @Override
        public QualifiedName getFqn(IFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QualifiedName getFqn(IPath path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IPath> getAllPossibleFilePaths(QualifiedName fqn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IPath getFilePath(QualifiedName fqn, EClass eClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IPath getFilePath(String fqn, EClass eClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getFilePath(QualifiedName fqn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getFilePath(String fqn) {
            throw new UnsupportedOperationException();
        }
    }

    private static class ThrowingConverter extends StubConverter {

        ThrowingConverter() {
            super(null);
        }

        @Override
        public QualifiedName getFqn(String path) {
            // The real converter asserts on paths it does not recognise.
            throw new AssertionError("unrecognised path"); //$NON-NLS-1$
        }
    }
}
