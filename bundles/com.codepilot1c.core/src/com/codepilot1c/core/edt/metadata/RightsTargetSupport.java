package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;

/**
 * Decides whether a rights grant can be <em>addressed</em> at a given metadata object at all — the
 * structural gate {@code rights_manage} applies once an FQN has resolved, before the per-right
 * applicability check.
 *
 * <p>Why this exists: {@link RightsModelUtil#isMdObjectHasRights(Object)} — the predicate that used
 * to be the whole gate — is
 * {@code ALL_SUPPORTED_RIGHT_ECLASSES.contains(o.eClass())}, an EXACT set membership over the
 * <em>top-level</em> metadata kinds (plus the four external-data-source leaf kinds FIELD / RESOURCE /
 * DIMENSION / FUNCTION). It says nothing about sub-objects, and because it compares by {@code equals}
 * rather than {@code isSuperTypeOf}, EVERY sub-object fails it: an HTTP service method, a URL
 * template, a web-service operation, a catalog/document attribute, a tabular section and its
 * attributes, an object command, a recalculation. Used alone it refused all of them with a message
 * claiming the platform grants no rights on such objects — verifiably false: a configuration's own
 * {@code Rights.rights} is full of
 * {@code <object><name>HTTPService.X.URLTemplate.T.Method.M</name>…} entries
 * (codepilot1c-feedback {@code 2026-09-11-rights-manage-false-object-does-not-support-rights-httpservice}).</p>
 *
 * <p>The platform's own answer for sub-objects is a DIFFERENT API of the same utility class:
 * {@link RightsModelUtil#getSubobjectEClasses(EObject)} maps a parent to the sub-object kinds that
 * carry rights under it ({@code HTTPService → {URLTemplate, Method}}, {@code URLTemplate → {Method}},
 * {@code WebService → {Operation}}, {@code CalculationRegister → {Recalculation, …}}, every
 * {@code BasicDbObject → {BasicFeature, Field, BasicCommand, BasicTabularSection}}, …), and
 * {@link RightsModelUtil#isSubobjectEClassHasRights(EObject, EClass)} matches a child against that
 * set <em>by supertype</em>. That is the pairing the platform rights editor itself uses to build its
 * tree, so it is the authority here too.</p>
 *
 * <p>This gate is deliberately structural, not capability-deciding. Whether the resolved kind
 * actually exposes any configurable right is decided downstream by
 * {@code IRightInfosService.getEClassRights} (e.g. a {@code URLTemplate} is addressable as a
 * sub-object yet carries no right of its own, and is refused there with a message about rights, not
 * about addressing). Keeping the two separate is what stops a single coarse predicate from speaking
 * for both questions again.</p>
 */
public final class RightsTargetSupport {

    private RightsTargetSupport() {
        // utility
    }

    /**
     * Whether a rights entry can be written against {@code target} at all: either its own kind is one
     * the rights model grants rights on, or it is a sub-object of a parent that carries rights on
     * that kind.
     */
    public static boolean isRightsAddressable(EObject target) {
        if (target == null) {
            return false;
        }
        if (RightsModelUtil.isMdObjectHasRights(target)) {
            return true;
        }
        EObject owner = target.eContainer();
        EClass targetEClass = RightsModelUtil.getEClass(target);
        // Single level up on purpose: getSubobjectEClasses already enumerates the kinds addressable
        // DIRECTLY under a given parent (a Method is listed under both HTTPService and URLTemplate),
        // so walking further would start accepting grandchildren their own parent never claimed.
        return owner != null && targetEClass != null
                && RightsModelUtil.isSubobjectEClassHasRights(owner, targetEClass);
    }

    /**
     * The refusal for the case that genuinely remains after {@link #isRightsAddressable(EObject)} —
     * a kind the EDT rights model places nowhere in the rights tree (a form, a template, an enum
     * value).
     *
     * <p>Scoped as a statement about this tool and the model it reads, NOT about what the 1C platform
     * supports. The previous wording ("Object does not support rights: …") asserted a platform
     * capability and, being wrong, sent a debugging session chasing whether the platform allows the
     * right at all — the explicit ask in the feedback note.</p>
     */
    public static String refusalMessage(String objectFqn, EObject target) {
        StringBuilder message = new StringBuilder();
        message.append("rights_manage cannot address rights on '").append(objectFqn) //$NON-NLS-1$
                .append("' (metadata kind: ").append(kindName(target)).append("): the EDT rights model "); //$NON-NLS-1$ //$NON-NLS-2$
        EObject owner = target == null ? null : target.eContainer();
        if (owner != null) {
            message.append("lists it neither as a rights-bearing object kind nor among the rights-bearing ") //$NON-NLS-1$
                    .append("sub-object kinds of its parent ").append(kindName(owner)) //$NON-NLS-1$
                    .append(" (").append(describeSubobjectKinds(owner)).append(")."); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            message.append("does not list it as a rights-bearing object kind."); //$NON-NLS-1$
        }
        message.append(" If the platform's rights editor does show a row for this object, that is a ") //$NON-NLS-1$
                .append("rights_manage gap worth reporting — not a limit of the platform."); //$NON-NLS-1$
        return message.toString();
    }

    private static String describeSubobjectKinds(EObject owner) {
        List<String> names = sortedKindNames(RightsModelUtil.getSubobjectEClasses(owner));
        if (names.isEmpty()) {
            return "it carries rights on no sub-object kind"; //$NON-NLS-1$
        }
        return "it carries rights on: " + String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<String> sortedKindNames(Set<EClass> eClasses) {
        List<String> names = new ArrayList<>();
        if (eClasses != null) {
            for (EClass eClass : eClasses) {
                if (eClass != null && eClass.getName() != null) {
                    names.add(eClass.getName());
                }
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static String kindName(EObject object) {
        if (object == null) {
            return "<unresolved>"; //$NON-NLS-1$
        }
        EClass eClass = RightsModelUtil.getEClass(object);
        if (eClass == null) {
            eClass = object.eClass();
        }
        return eClass == null || eClass.getName() == null ? "<unknown>" : eClass.getName(); //$NON-NLS-1$
    }
}
