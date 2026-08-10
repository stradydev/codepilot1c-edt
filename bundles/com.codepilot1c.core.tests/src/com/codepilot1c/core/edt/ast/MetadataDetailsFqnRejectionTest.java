package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the {@code edt_metadata_details} miss message.
 *
 * <p>The tool resolves only a top-level {@code <Type>.<Name>} FQN, but it used to read that pair out of
 * a LONGER dotted FQN and return the object it found — so asking for
 * {@code Subsystem.<Parent>.<Child>} answered with the PARENT's properties under the requested path
 * (observed live 2026-07-28). A silent wrong answer is worse than a false negative: the caller has no
 * signal at all. The miss is now explicit and names the supported form.</p>
 */
public class MetadataDetailsFqnRejectionTest {

    @Test
    public void aSupportedKindMissSaysTheKindWasActuallySearched() {
        String message = EdtMetadataInspectorService.notFoundMessage("Catalog.NoSuchThing"); //$NON-NLS-1$
        assertTrue("the miss must still read as a miss", message.startsWith("Object not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a supported kind was searched — say so, or the caller suspects a tool gap", //$NON-NLS-1$
                message.contains("IS supported")); //$NON-NLS-1$
    }

    @Test
    public void aSupportedKindMissNamesTheProjectItSearched() {
        // Two live reports blamed the tool for Role/ScheduledJob "not found" when the real cause was
        // the object belonging to another project. Naming the project settles that without a probe.
        String message = EdtMetadataInspectorService.notFoundMessage(
                "Role.Accountant", "TestConfiguration"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message.contains("TestConfiguration")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownKindIsNotReportedAsAMissingObject() {
        String message = EdtMetadataInspectorService.notFoundMessage("NotAKind.Whatever"); //$NON-NLS-1$
        assertTrue("an unrecognized type token is a different failure from a missing object", //$NON-NLS-1$
                message.contains("Unsupported metadata kind")); //$NON-NLS-1$
        assertTrue("the answer must not be read as proof of absence", //$NON-NLS-1$
                message.contains("not evidence")); //$NON-NLS-1$
    }

    @Test
    public void supportedKindsKeepResolvingTheirToken() {
        // Role and ScheduledJob were mapped in 7884add; pin that they are not reported as unsupported.
        for (String fqn : new String[] {"Role.Any", "ScheduledJob.Any", "CommonModule.Any"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(fqn + " must be treated as a supported kind", //$NON-NLS-1$
                    EdtMetadataInspectorService.notFoundMessage(fqn).contains("IS supported")); //$NON-NLS-1$
        }
    }

    @Test
    public void aNestedDottedFqnIsToldWhyItCannotResolve() {
        String message = EdtMetadataInspectorService.notFoundMessage("Subsystem.Parent.Child"); //$NON-NLS-1$
        assertTrue("the caller must learn the extra segments are the problem", //$NON-NLS-1$
                message.contains("extra segments")); //$NON-NLS-1$
        assertTrue("the flat subsystem form is the whole point of the hint", //$NON-NLS-1$
                message.contains("Subsystem.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void aChildObjectFqnIsRejectedTheSameWay() {
        String message = EdtMetadataInspectorService.notFoundMessage("Catalog.Goods.Attribute.Price"); //$NON-NLS-1$
        assertTrue("child objects are out of scope for this tool — say so", //$NON-NLS-1$
                message.contains("not addressable")); //$NON-NLS-1$
    }

    @Test
    public void aDegenerateFqnDoesNotBlowUpAndAsksForATypeToken() {
        assertTrue(EdtMetadataInspectorService.notFoundMessage(null).startsWith("Object not found")); //$NON-NLS-1$
        String message = EdtMetadataInspectorService.notFoundMessage("Configuration"); //$NON-NLS-1$
        assertTrue("a bare name carries no type token — name the expected shape", //$NON-NLS-1$
                message.contains("no type token")); //$NON-NLS-1$
    }
}
