package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Method;
import com._1c.g5.v8.dt.metadata.mdclass.Operation;
import com._1c.g5.v8.dt.metadata.mdclass.Recalculation;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.WebService;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;

/**
 * Behavioural regression for the {@code rights_manage} structural gate, over real EMF metadata
 * objects rather than over the text of the source that builds them.
 *
 * <p>Background: codepilot1c-feedback
 * {@code 2026-09-11-rights-manage-false-object-does-not-support-rights-httpservice}. A {@code Use}
 * grant on {@code HTTPService.MCPApi.URLTemplate.V1Whoami.Method.Get} was refused with
 * "Object does not support rights", while the very same workspace carries dozens of committed
 * {@code <object><name>HTTPService.Inventory.URLTemplate.Application.Method.Get</name>…} grants. The
 * gate was {@code RightsModelUtil.isMdObjectHasRights} alone — an exact eClass-set membership over
 * top-level kinds — so it refused every sub-object, HTTP methods and catalog attributes alike, and
 * blamed the platform for it.</p>
 *
 * <p>The tests below therefore pin two things at once: the platform fact that made the old gate
 * wrong (so a future platform change is noticed rather than silently absorbed), and the new
 * behaviour of {@link RightsTargetSupport} for each shape the old gate refused.</p>
 */
public class RightsTargetSupportTest {

    private static final MdClassFactory FACTORY = MdClassFactory.eINSTANCE;

    // --- the reported defect: HTTPService.<S>.URLTemplate.<T>.Method.<M> ------------------------

    @Test
    public void httpServiceMethodIsRightsAddressable() {
        assertTrue("a grant on HTTPService.X.URLTemplate.T.Method.M must be addressable — the base" //$NON-NLS-1$
                + " configuration carries dozens of exactly this shape on disk", //$NON-NLS-1$
                RightsTargetSupport.isRightsAddressable(httpServiceMethod()));
    }

    @Test
    public void theOldCoarsePredicateIsExactlyWhatRefusedTheMethod() {
        // Root-cause pin, not a tautology: isMdObjectHasRights is ALL_SUPPORTED_RIGHT_ECLASSES
        // .contains(eClass) — an equals-membership over top-level kinds. If a future EDT widens it,
        // this test fails and the extra sub-object handling can be revisited.
        assertFalse("the platform's top-level predicate must still be the wrong question for a" //$NON-NLS-1$
                + " sub-object — that is why RightsTargetSupport exists", //$NON-NLS-1$
                RightsModelUtil.isMdObjectHasRights(httpServiceMethod()));
    }

    @Test
    public void urlTemplateItselfIsAddressableAsASubobject() {
        // Addressable is not the same as "carries a right": a URL template is a node of the rights
        // tree with no RightInfo of its own, and must be refused downstream by the rights-catalogue
        // check with a message about rights — not here with one about the object kind.
        HTTPService service = FACTORY.createHTTPService();
        service.setName("Inventory"); //$NON-NLS-1$
        URLTemplate template = FACTORY.createURLTemplate();
        template.setName("Application"); //$NON-NLS-1$
        service.getUrlTemplates().add(template);

        assertTrue(RightsTargetSupport.isRightsAddressable(template));
    }

    @Test
    public void topLevelHttpServiceStaysAddressable() {
        HTTPService service = FACTORY.createHTTPService();
        service.setName("Inventory"); //$NON-NLS-1$
        assertTrue(RightsTargetSupport.isRightsAddressable(service));
    }

    // --- the same root cause, silently broken for every other sub-object shape -----------------

    @Test
    public void catalogAttributeIsAddressableAndWasRefusedByTheOldGate() {
        Catalog catalog = FACTORY.createCatalog();
        catalog.setName("Contracts"); //$NON-NLS-1$
        CatalogAttribute attribute = FACTORY.createCatalogAttribute();
        attribute.setName("Sum"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        assertFalse("attributes were refused by the same predicate as HTTP methods", //$NON-NLS-1$
                RightsModelUtil.isMdObjectHasRights(attribute));
        assertTrue(RightsTargetSupport.isRightsAddressable(attribute));
    }

    @Test
    public void catalogTabularSectionAndItsOwnAttributeAreAddressable() {
        Catalog catalog = FACTORY.createCatalog();
        catalog.setName("Contracts"); //$NON-NLS-1$
        CatalogTabularSection section = FACTORY.createCatalogTabularSection();
        section.setName("Lines"); //$NON-NLS-1$
        catalog.getTabularSections().add(section);
        TabularSectionAttribute nested = FACTORY.createTabularSectionAttribute();
        nested.setName("Price"); //$NON-NLS-1$
        section.getAttributes().add(nested);

        assertTrue(RightsTargetSupport.isRightsAddressable(section));
        assertTrue("an attribute one level deeper resolves against its own parent, the tabular" //$NON-NLS-1$
                + " section — not against the catalog", //$NON-NLS-1$
                RightsTargetSupport.isRightsAddressable(nested));
    }

    @Test
    public void objectCommandIsAddressable() {
        Catalog catalog = FACTORY.createCatalog();
        catalog.setName("Contracts"); //$NON-NLS-1$
        CatalogCommand command = FACTORY.createCatalogCommand();
        command.setName("Recalculate"); //$NON-NLS-1$
        catalog.getCommands().add(command);

        assertTrue(RightsTargetSupport.isRightsAddressable(command));
    }

    @Test
    public void recalculationIsAddressable() {
        CalculationRegister register = FACTORY.createCalculationRegister();
        register.setName("Accruals"); //$NON-NLS-1$
        Recalculation recalculation = FACTORY.createRecalculation();
        recalculation.setName("ByEmployee"); //$NON-NLS-1$
        register.getRecalculations().add(recalculation);

        assertTrue(RightsTargetSupport.isRightsAddressable(recalculation));
    }

    @Test
    public void webServiceOperationIsAddressable() {
        WebService service = FACTORY.createWebService();
        service.setName("Exchange"); //$NON-NLS-1$
        Operation operation = FACTORY.createOperation();
        operation.setName("Ping"); //$NON-NLS-1$
        service.getOperations().add(operation);

        assertTrue(RightsTargetSupport.isRightsAddressable(operation));
    }

    // --- the refusal that legitimately remains --------------------------------------------------

    @Test
    public void enumValueIsNotAddressable() {
        assertFalse("an Enum carries rights only on its commands; a value is not a rights node", //$NON-NLS-1$
                RightsTargetSupport.isRightsAddressable(enumValue()));
    }

    @Test
    public void objectFormIsNotAddressable() {
        Catalog catalog = FACTORY.createCatalog();
        catalog.setName("Contracts"); //$NON-NLS-1$
        CatalogForm form = FACTORY.createCatalogForm();
        form.setName("ListForm"); //$NON-NLS-1$
        catalog.getForms().add(form);

        assertFalse("only CommonForm carries rights; an object's own form does not", //$NON-NLS-1$
                RightsTargetSupport.isRightsAddressable(form));
    }

    @Test
    public void aDetachedObjectIsNotAddressable() {
        assertFalse("without a container there is no parent whose sub-object kinds could cover it", //$NON-NLS-1$
                RightsTargetSupport.isRightsAddressable(FACTORY.createMethod()));
        assertFalse(RightsTargetSupport.isRightsAddressable(null));
    }

    // --- the message: a tool limit, never a claim about the platform ----------------------------

    @Test
    public void refusalMessageNeverAssertsThePlatformGrantsNoRights() {
        String message = RightsTargetSupport.refusalMessage(
                "Enum.PaymentKinds.EnumValue.Cash", enumValue()); //$NON-NLS-1$

        assertFalse("the old wording asserted a platform capability and was verifiably false," //$NON-NLS-1$
                + " which cost a debugging session: " + message, //$NON-NLS-1$
                message.contains("does not support rights")); //$NON-NLS-1$
        assertTrue("the refusal must name itself as the tool's limit: " + message, //$NON-NLS-1$
                message.contains("rights_manage")); //$NON-NLS-1$
        assertTrue("the caller needs the FQN back: " + message, //$NON-NLS-1$
                message.contains("Enum.PaymentKinds.EnumValue.Cash")); //$NON-NLS-1$
        assertTrue("the resolved metadata kind explains WHY it was refused: " + message, //$NON-NLS-1$
                message.contains("EnumValue")); //$NON-NLS-1$
        assertTrue("naming what the parent DOES carry rights on is the actionable half: " + message, //$NON-NLS-1$
                message.contains("EnumCommand") || message.contains("BasicCommand")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void refusalMessageSurvivesAnObjectWithNoContainer() {
        String message = RightsTargetSupport.refusalMessage("Method.Detached", FACTORY.createMethod()); //$NON-NLS-1$
        assertTrue(message.contains("Method.Detached")); //$NON-NLS-1$
        assertFalse(message.contains("does not support rights")); //$NON-NLS-1$
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** {@code HTTPService.Inventory.URLTemplate.Application.Method.Get} — the reported shape. */
    private static Method httpServiceMethod() {
        HTTPService service = FACTORY.createHTTPService();
        service.setName("Inventory"); //$NON-NLS-1$
        URLTemplate template = FACTORY.createURLTemplate();
        template.setName("Application"); //$NON-NLS-1$
        service.getUrlTemplates().add(template);
        Method method = FACTORY.createMethod();
        method.setName("Get"); //$NON-NLS-1$
        template.getMethods().add(method);
        return method;
    }

    private static EnumValue enumValue() {
        // Fully qualified on purpose: a single-type import of mdclass.Enum would shadow java.lang.Enum
        // for the whole file.
        com._1c.g5.v8.dt.metadata.mdclass.Enum enumeration = FACTORY.createEnum();
        enumeration.setName("PaymentKinds"); //$NON-NLS-1$
        EnumValue value = FACTORY.createEnumValue();
        value.setName("Cash"); //$NON-NLS-1$
        enumeration.getEnumValues().add(value);
        return value;
    }
}
