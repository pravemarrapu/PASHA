/*
 * Copyright (c) 2017 WeServe LLC. All Rights Reserved.
 *
 */


import com.navis.argo.*
import com.navis.argo.business.atoms.FreightKindEnum
import com.navis.argo.business.atoms.UnitCategoryEnum
import com.navis.argo.business.reference.EquipType
import com.navis.argo.business.reference.Equipment
import com.navis.edi.business.edimodel.EdiConsts
import com.navis.edi.business.entity.EdiBatch
import com.navis.external.edi.entity.AbstractEdiPostInterceptor
import com.navis.framework.business.Roastery
import com.navis.framework.persistence.HibernateApi
import com.navis.framework.util.BizFailure
import com.navis.framework.util.message.MessageCollector
import com.navis.framework.util.message.MessageCollectorUtils
import com.navis.inventory.business.api.UnitFinder
import com.navis.inventory.business.atoms.UnitVisitStateEnum
import com.navis.inventory.business.units.EqBaseOrder
import com.navis.inventory.business.units.Unit
import org.apache.xmlbeans.XmlObject

/**
 * This Edi Post Interceptor is written to update the flex field "UnitFlexString12" with VGM Weight.
 * If the weight is given in LB's it will update the same, if the weight is given in KG's value will be updated by
 * multipplying with 2.20462.
 *
 * This interceptor will skip the poster
 */

/*
 *
 * @Author <a href="mailto:peswendra@weservetech.com">Eswendra Reddy</a>, 08/Sep/2017
 *
 * Requirements : This groovy is used to update the flex field "UnitFlexString12" with VGM Weight.
 * If the weight is given in LB's it will update the same, if the weight is given in KG's value will be updated by
 * multipplying with 2.20462.
 *
 * @Inclusion Location : code extension need to tied to EDI Session under Post Code Extension as mention below.
 *
 * Deployment Steps:
 * a) Administration -> System -> Code Extension
 * b) Click on + (Add) Button
 * c) Add as EDI_POST_INTERCEPTOR and code extension name as PashaUpdateVGMWeightToFlexField
 * d) Paste the groovy code and click on save
 *
 */

class PashaUpdateVGMWeightToFlexField extends AbstractEdiPostInterceptor {

    @Override
    public void beforeEdiPost(XmlObject inXmlTransactionDocument, Map inParams) {
        log("Inside PashaUpdateVGMWeightToFlexField :: START");

        Serializable batchGKey = inParams.get("BATCH_GKEY");
        EdiBatch ediBatch = (EdiBatch) HibernateApi.getInstance().load(EdiBatch.class, batchGKey);

        inParams.put(EdiConsts.SKIP_POSTER, Boolean.TRUE);

        PreadviseTransactionsDocument preadviseDocument = (PreadviseTransactionsDocument) inXmlTransactionDocument;
        PreadviseTransactionsDocument.PreadviseTransactions preadviseTransactions = preadviseDocument.getPreadviseTransactions();
        List<PreadviseTransactionDocument.PreadviseTransaction> list = preadviseTransactions.getPreadviseTransactionList();

        if (list.isEmpty()) {
            throw BizFailure.create("There is no transaction in the batch");
        }
        try {
            for (PreadviseTransactionDocument.PreadviseTransaction preadviseTransaction : list) {
                EdiContainer ctr = preadviseTransaction.getEdiContainer()
                if (ctr == null) {
                    appendToMessageCollector("File Does not contain container number");
                    log("File Does not contain container number");
                    ContextHelper.getThreadEdiPostingContext().throwIfAnyViolations();
                } else {
                    String ctrNbr = ctr.getContainerNbr()
                    log("Container Number " + ctrNbr);
                    if (ctrNbr == null) {
                        appendToMessageCollector("File Does not contain container number");
                        log("File Does not contain container number");
                    }

                    EdiBooking booking = preadviseTransaction.getEdiBooking();
                    if (booking == null) {
                        appendToMessageCollector("File Does not contain booking number");
                        log("File Does not consist booking details");
                    }

                    EdiFlexFields flexFields = preadviseTransaction.getEdiFlexFields();

                    Unit activeUnit = getUnit(ctrNbr);

                    EqBaseOrder bookingNbr = null;
                    log("Transaction Unit " + activeUnit);
                    if (activeUnit != null) {
                        bookingNbr = activeUnit.getDepartureOrder();
                    }
                    if (activeUnit == null) {
                        appendToMessageCollector("Unit does not exists");
                        log("Unit does not exists" + activeUnit);
                    }

                    EdiOperator ctrOperator = ctr.getContainerOperator();
                    String operator = ctrOperator.getOperator();
                    log("Unit Line Operator" + activeUnit.getUnitLineOperator().getBzuId() + " " + operator)
                    if (activeUnit.getUnitLineOperator().getBzuId().equals(operator)) {
                        String ctrGrossWt = ctr.getContainerGrossWtUnit();
                        String bkgNbr = booking.getBookingNbr();
                        String vesId = null;

                        EdiCarrierVisit vessel = preadviseTransaction.getEdiOutboundVisit();

                        if (vessel != null) {
                            EdiVesselVisit vesselVisit = vessel.getEdiVesselVisit();
                            if (vesselVisit != null) {
                                vesId = vesselVisit.getVesselId();
                            }
                        }

                        if (UnitVisitStateEnum.ACTIVE.equals(activeUnit.getUnitVisitState())) {
                            log("For the Unit " + activeUnit + "Updating the flex field value");

                            if (bookingNbr != null && bookingNbr.getEqboNbr().equals(bkgNbr) && activeUnit.getUnitActiveUfvNowActive() != null &&
                                    (activeUnit.getUnitActiveUfvNowActive().getUfvActualObCv().getCvId().equals(vesId) || vesId == null)) {
                                Equipment equipment = Equipment.findEquipment(ctrNbr);

                                if (FreightKindEnum.MTY.equals(activeUnit.getUnitFreightKind())) {
                                    //if (VgmWt == null || ctrGrossWt == null || vessel == null) {
                                    log("Freight Kind is Empty, updating Equipment Tare Wt" + equipment.getEqTareWeightKg());
                                    Double ctrTareWt = equipment.getEqTareWeightKg();
                                    activeUnit.setUnitFlexString12(ctrTareWt.toString());
                                    //}
                                } else if (FreightKindEnum.FCL.equals(activeUnit.getUnitFreightKind()) || FreightKindEnum.LCL.equals(activeUnit.getUnitFreightKind())) {
                                    log("Inside FCL/LCL condition");
                                    if (flexFields == null) {
                                        appendToMessageCollector("File Does not contain proper weight");
                                        log("File Does not contain proper weight");
                                    }
                                    String VgmWt = flexFields.getUnitFlexString12();
                                    if (VgmWt == null) {
                                        appendToMessageCollector("File Does not contain proper weight");
                                        log("File Does not contain proper weight");
                                    }
                                    if (ctrGrossWt == null) {
                                        appendToMessageCollector("File Does not contain Container Gross Weight");
                                        log("File Does not contain Container Gross Weight");
                                    }
                                    if (ctrGrossWt.equals("QT") || ctrGrossWt.equals("LT") || ctrGrossWt.equals("ST") || ctrGrossWt.equals("MT")) {
                                        appendToMessageCollector("Weight type is not correct");
                                        log("Weight type is not correct");
                                    }
                                    if (VgmWt.toDouble() < equipment.getEqTareWeightKg()) {
                                        appendToMessageCollector("Weight is less tare weight");
                                        log("Weight is less tare weight");
                                    }

                                    if (equipment.getEqSafeWeightKg() == 0.0D) {
                                        EquipType eqType = equipment.getEqEquipType();
                                        Double eqTypeSafeWt = eqType.getEqtypSafeWeightKg();
                                        if (eqTypeSafeWt == 0.0D) {
                                            updateVgmWeight(ctrGrossWt, VgmWt, activeUnit);
                                        } else if (eqTypeSafeWt > 0.0D) {
                                            if (VgmWt.toDouble() > eqTypeSafeWt) {
                                                appendToMessageCollector("Weight is greater than equipment type safe weight");
                                                log("Weight is greater than equipment type safe weight");
                                            } else {
                                                updateVgmWeight(ctrGrossWt, VgmWt, activeUnit);
                                            }
                                        }
                                    } else {
                                        if (VgmWt.toDouble() > equipment.getEqSafeWeightKg()) {
                                            appendToMessageCollector("Weight is greater than safe weight");
                                            log("Weight is greater than safe weight");
                                        } else {
                                            updateVgmWeight(ctrGrossWt, VgmWt, activeUnit);
                                        }
                                    }
                                    HibernateApi.getInstance().save(activeUnit);
                                    HibernateApi.getInstance().flush();
                                }
                            } else {
                                if (activeUnit.getUnitActiveUfvNowActive() != null && !(activeUnit.getUnitActiveUfvNowActive().getUfvActualObCv().getCvId().equals(vesId))) {
                                    appendToMessageCollector("Vessel Visit mismatch with Unit");
                                    log("Vessel Visit mismatch with Unit");
                                }
                                if (bookingNbr == null) {
                                    appendToMessageCollector("No booking associated with Unit");
                                    log("No booking associated with Unit");
                                } else if (!(bookingNbr.getEqboNbr().equals(bkgNbr))) {
                                    appendToMessageCollector("Booking mismatch with Unit");
                                    log("Booking mismatch with Unit");
                                }
                            }
                        }
                    } else {
                        appendToMessageCollector("Line Operator mismatch with Unit");
                        log("Line Operator  mismatch with Unit");
                    }
                }

            }
        } finally {
            inParams.put(EdiConsts.SKIP_POSTER, Boolean.TRUE);
        }
    }

    private Unit getUnit(String inCtrNbr) {
        Equipment eq = Equipment.findEquipment(inCtrNbr);
        if (eq == null) {
            appendToMessageCollector("Equipment does not exists");
            log("Equipment does not exists");
        }
        UnitFinder unitFinder = (UnitFinder) Roastery.getBean(UnitFinder.BEAN_ID);
        Unit unit = unitFinder.findActiveUnit(ContextHelper.getThreadComplex(), eq, UnitCategoryEnum.EXPORT);
        return unit;
    }

    private void updateVgmWeight(String inCtrGrossWt, String inVgmWt, Unit inActiveUnit) {
        if ("KG".equals(inCtrGrossWt)) {
            log("KG value setting to flexfield");
            Double updatedVgmWt = 0.0D;
            if (inVgmWt != null) {
                updatedVgmWt = inVgmWt.toDouble() * 2.20462;
                log("After Update" + inVgmWt);
                long roundedValue = Math.round(updatedVgmWt);
                log("After rounding" + roundedValue);
                inActiveUnit.setUnitFlexString12(roundedValue.toString());
            }
        } else if ("LB".equals(inCtrGrossWt)) {
            log("LB value setting to flexfield");
            inActiveUnit.setUnitFlexString12(inVgmWt);
        }
    }

    private void appendToMessageCollector(String inMessage) {
        MessageCollectorUtils.getMessageCollector().appendMessage(BizFailure
                .create(ArgoPropertyKeys.INFO, null, inMessage));
    }
}