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
import com.navis.framework.business.atoms.MassUnitEnum
import com.navis.framework.metafields.Measured
import com.navis.framework.metafields.MetafieldId
import com.navis.framework.persistence.HibernateApi
import com.navis.framework.presentation.FrameworkPresentationUtils
import com.navis.framework.util.BizFailure
import com.navis.framework.util.message.MessageCollectorUtils
import com.navis.framework.util.unit.MeasurementUnit
import com.navis.framework.util.unit.UnitUtils
import com.navis.inventory.business.api.UnitFinder
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
                Unit activeUnit
                if (canProceedToUpdate(activeUnit, preadviseTransaction)) {
                    log("Active Unit :: " + activeUnit);
                    activeUnit = getUnit(preadviseTransaction.getEdiContainer().getContainerNbr())
                    EdiContainer ctr = preadviseTransaction.getEdiContainer()
                    Equipment equipment = activeUnit.getUnitEquipment();
                    log("Current Equipment :: " + equipment);
                    String ctrGrossWt = ctr.getContainerGrossWtUnit();

                    Double ctrTareWt = 0.0D;
                    long tareWtRoundedValue = 0L;
                    String tareAbbrevation = getMeasureType(ArgoRefField.EQ_TARE_WEIGHT_KG);
                    ctrTareWt = equipment.getEqTareWeightKg();
                    tareWtRoundedValue = weightConvertionForN4Fields(tareAbbrevation, ctrTareWt);

                    Double ctrSafeWt = 0.0D;
                    long safeWtRoundedValue = 0L;
                    String safeAbbrevation = getMeasureType(ArgoRefField.EQ_SAFE_WEIGHT_KG);
                    ctrSafeWt = equipment.getEqSafeWeightKg();
                    safeWtRoundedValue = weightConvertionForN4Fields(safeAbbrevation, ctrSafeWt);

                    if (FreightKindEnum.MTY.equals(activeUnit.getUnitFreightKind())) {
                        log("Freight Kind is Empty, updating Equipment Tare Wt" + equipment.getEqTareWeightKg());
                        activeUnit.setUnitFlexString12(tareWtRoundedValue.toString());
                    } else if (FreightKindEnum.FCL.equals(activeUnit.getUnitFreightKind()) || FreightKindEnum.LCL.equals(activeUnit.getUnitFreightKind())) {
                        log("Inside FCL/LCL condition");
                        EdiFlexFields flexFields = preadviseTransaction.getEdiFlexFields();
                        if (flexFields == null) {
                            appendToMessageCollector("File Does not contain proper weight");
                            log("File Does not contain proper weight");
                        } else {
                            String VgmWt = flexFields.getUnitFlexString12();
                            if (VgmWt == null) {
                                appendToMessageCollector("File Does not contain proper weight");
                                log("File Does not contain proper weight");
                            }

                            String convertedVgmWt = weightConvertionForVGM(ctrGrossWt, VgmWt, activeUnit);
                            long vgmRoundedValue = convertedVgmWt.toLong();
                            if (vgmRoundedValue < tareWtRoundedValue) {
                                appendToMessageCollector("Weight is less tare weight");
                                log("Weight is less tare weight");
                            }

                            /*if (VgmWt.toDouble() < equipment.getEqTareWeightKg()) {
                                appendToMessageCollector("Weight is less tare weight");
                                log("Weight is less tare weight");
                            }*/

                            if (safeWtRoundedValue == 0.0D) {
                                EquipType eqType = equipment.getEqEquipType();
                                Double eqTypeSafeWt = 0.0D;
                                long eqTypeSafeWtRoundedValue = 0L;
                                String eqTypeSafeAbbrevation = getMeasureType(ArgoRefField.EQTYP_SAFE_WEIGHT_KG);
                                eqTypeSafeWt = eqType.getEqtypSafeWeightKg();
                                eqTypeSafeWtRoundedValue = weightConvertionForN4Fields(eqTypeSafeAbbrevation, eqTypeSafeWt);

                                if (eqTypeSafeWtRoundedValue == 0.0D) {
                                    activeUnit.setUnitFlexString12(convertedVgmWt);
                                } else if (eqTypeSafeWtRoundedValue > 0.0D) {
                                    if (vgmRoundedValue > eqTypeSafeWtRoundedValue) {
                                        log("VgmRoundedValue " + vgmRoundedValue + "EqTypeSafeWt " + eqTypeSafeWt)
                                        appendToMessageCollector("Weight is greater than equipment type safe weight");
                                        log("Weight is greater than equipment type safe weight");
                                    } else {
                                        activeUnit.setUnitFlexString12(convertedVgmWt);
                                    }
                                }
                            } else {
                                log("Vgm ROunded Value " + vgmRoundedValue);
                                if (vgmRoundedValue > safeWtRoundedValue) {
                                    appendToMessageCollector("Weight is greater than safe weight");
                                    log("Weight is greater than safe weight");
                                } else {
                                    activeUnit.setUnitFlexString12(convertedVgmWt);
                                }
                            }
                            HibernateApi.getInstance().save(activeUnit);
                            HibernateApi.getInstance().flush();
                        }
                    } else {
                        ContextHelper.getThreadEdiPostingContext().throwIfAnyViolations();
                    }
                }
            }
        } finally {
            inParams.put(EdiConsts.SKIP_POSTER, Boolean.TRUE);
        }
    }

    private boolean canProceedToUpdate(Unit inUnit, PreadviseTransactionDocument.PreadviseTransaction preadviseTransaction) {
        EdiContainer ctr = preadviseTransaction.getEdiContainer();
        if (ctr == null) {
            appendToMessageCollector("File Does not contain container number")
            return false;
        }
        if (ctr.getContainerNbr() == null) {
            appendToMessageCollector("File Does not contain container number");
            return false;
        } else {
            String ctrGrossWtUnit = ctr.getContainerGrossWtUnit();
            inUnit = getUnit(ctr.getContainerNbr());
            if (inUnit == null) {
                appendToMessageCollector("Unit does not exists");
                return false
            }
            if (!FreightKindEnum.MTY.equals(inUnit.getUnitFreightKind()) && ctrGrossWtUnit == null) {
                appendToMessageCollector("File Does not contain Container Gross Weight");
                return false;
            }
            if (!FreightKindEnum.MTY.equals(inUnit.getUnitFreightKind()) && ctrGrossWtUnit.equals("QT") || ctrGrossWtUnit.equals("LT") || ctrGrossWtUnit.equals("ST") || ctrGrossWtUnit.equals("MT")) {
                appendToMessageCollector("Weight type is not correct");
                return false;
            }
            if (inUnit.getUnitLineOperator() != null && ctr.getContainerOperator() != null
                    && !(inUnit.getUnitLineOperator().getBzuId().equals(ctr.getContainerOperator().getOperator()))) {
                appendToMessageCollector("Line Operator mismatch with Unit");
                return false
            }
            EqBaseOrder unitBooking = inUnit.getDepartureOrder();
            if (unitBooking == null) {
                appendToMessageCollector("No booking associated with Unit");
                return false
            }
            EdiBooking ediBooking = preadviseTransaction.getEdiBooking();
            if (ediBooking == null) {
                appendToMessageCollector("File Does not contain booking number");
                return false;
            }
            if (!unitBooking.getEqboNbr().equals(ediBooking.getBookingNbr())) {
                appendToMessageCollector("Booking mismatch with Unit");
                return false
            }

            String vesId = null;
            EdiCarrierVisit vessel = preadviseTransaction.getEdiOutboundVisit();
            if (vessel != null) {
                EdiVesselVisit vesselVisit = vessel.getEdiVesselVisit();
                if (vesselVisit != null) {
                    vesId = vesselVisit.getVesselId();
                }
            }
            if (inUnit.getUnitActiveUfvNowActive() != null && inUnit.getUnitActiveUfvNowActive().getUfvActualObCv().getCvId() &&
                    !(inUnit.getUnitActiveUfvNowActive().getUfvActualObCv().getCvId().equals(vesId) || vesId == null)) {
                appendToMessageCollector("Vessel Visit mismatch with Unit");
                return false;
            }
        }
        return true;
    }

    /**
     * To get the actve export unit for the provided container number
     *
     * @param inCtrNbr
     * @return
     */
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

    /**
     * Method to Kilogram to convertPound
     *
     * @return
     */
    private String convertKgtoLb(String inVgmWt) {
        Double valueInLb = UnitUtils.convertTo(inVgmWt, MassUnitEnum.POUNDS, MassUnitEnum.KILOGRAMS);
        log("valueInKg " + inVgmWt + " => valueInLb : " + String.valueOf(valueInLb));
        return String.valueOf(valueInLb);
    }

    /**
     * This method will convert the VGM weight to lb's
     * @param inCtrGrossWt
     * @param inVgmWt
     * @param inActiveUnit
     * @return
     */
    private String weightConvertionForVGM(String inCtrGrossWt, String inVgmWt, Unit inActiveUnit) {
        String roundedValue;
        if ("KG".equals(inCtrGrossWt)) {
            log("KG value setting to flexfield");
            roundedValue = convertKgtoLb(inCtrGrossWt);
            return roundedValue;
        } else if ("LB".equals(inCtrGrossWt)) {
            log("LB value setting to flexfield");
            roundedValue = inVgmWt;
            return roundedValue;
        }
        return roundedValue;
    }

    /**
     * This method will convert the Equipment Tare wt, Equipment type tare wt and Equipment type safe wt fields of N4 to Lb's
     * @param inAbbrevation
     * @param inCtrWt
     * @return
     */
    private long weightConvertionForN4Fields(String inAbbrevation, Double inCtrWt) {
        if ("kg".equals(inAbbrevation) || "KG".equals(inAbbrevation)) {
            String roundedValue = convertKgtoLb(inCtrWt.toString());
            log("Converted Value" + roundedValue);
            return Math.round(roundedValue.toDouble());
        } else if ("lb".equals(inAbbrevation) || "LB".equals(inAbbrevation)) {
            String roundedValue = convertKgtoLb(inCtrWt.toString());
            log("Converted Value" + roundedValue);
            return Math.round(roundedValue.toDouble());
        }
        return 0L;
    }

    /**
     * This method will return Mass unit of the provided metafield
     * @param inMetaField
     * @return
     */
    private String getMeasureType(MetafieldId inMetaField) {
        Measured tareMeasure = FrameworkPresentationUtils.getMetafield(inMetaField).getMeasured();
        MeasurementUnit tareMeasureUnit = tareMeasure.getUserUnit();
        String tareAbbrevation = tareMeasureUnit.getAbbrevation();
        log("Weight Abbrevation " + tareAbbrevation);
        log("Weight User Measure Unit" + tareMeasureUnit.toString());
        return tareAbbrevation;
    }

    /**
     * Message Collector
     * @param inMessage
     */
    private void appendToMessageCollector(String inMessage) {
        MessageCollectorUtils.getMessageCollector().appendMessage(BizFailure
                .create(ArgoPropertyKeys.INFO, null, inMessage));
    }
}