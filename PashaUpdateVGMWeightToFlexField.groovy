/*
 * Copyright (c) 2017 WeServe LLC. All Rights Reserved.
 *
 */


import com.navis.argo.ArgoPropertyKeys
import com.navis.argo.ContextHelper
import com.navis.argo.EdiBooking
import com.navis.argo.EdiCarrierVisit
import com.navis.argo.EdiContainer
import com.navis.argo.EdiFlexFields
import com.navis.argo.EdiVesselVisit
import com.navis.argo.PreadviseTransactionDocument
import com.navis.argo.PreadviseTransactionsDocument
import com.navis.argo.business.atoms.FreightKindEnum
import com.navis.argo.business.atoms.UnitCategoryEnum
import com.navis.argo.business.reference.EquipType
import com.navis.argo.business.reference.Equipment
import com.navis.argo.presentation.controller.EquipmentTypeFormController
import com.navis.edi.business.edimodel.EdiConsts
import com.navis.edi.business.entity.EdiBatch
import com.navis.external.edi.entity.AbstractEdiPostInterceptor
import com.navis.framework.business.Roastery
import com.navis.framework.business.atoms.MassUnitEnum
import com.navis.framework.metafields.Measured
import com.navis.framework.metafields.Metafield
import com.navis.framework.metafields.MetafieldId
import com.navis.framework.persistence.HibernateApi
import com.navis.framework.presentation.FrameworkPresentationUtils
import com.navis.framework.presentation.ui.ICarinaWidget
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
                    ctrTareWt = equipment.getEqTareWeightKg();

                    Double ctrSafeWt = 0.0D;
                    ctrSafeWt = equipment.getEqSafeWeightKg();

                    if (FreightKindEnum.MTY.equals(activeUnit.getUnitFreightKind())) {
                        log("Freight Kind is Empty, updating Equipment Tare Wt" + equipment.getEqTareWeightKg());
                        activeUnit.setUnitFlexString12(ctrTareWt.toString());
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
                            Double vgmRoundedValue = convertedVgmWt.toDouble();
                            if (vgmRoundedValue < ctrTareWt) {
                                appendToMessageCollector("Weight is less tare weight");
                                log("Weight is less tare weight");
                            }

                            if (ctrSafeWt == 0.0D) {
                                EquipType eqType = equipment.getEqEquipType();
                                Double eqTypeSafeWt = 0.0D;
                                eqTypeSafeWt = eqType.getEqtypSafeWeightKg();

                                if (eqTypeSafeWt == 0.0D) {
                                    String roundedValue = convertKgtoLb(convertedVgmWt);
                                    long lbRoundedVal = Math.round(roundedValue.toDouble());
                                    activeUnit.setUnitFlexString12(lbRoundedVal.toString());
                                } else if (eqTypeSafeWt > 0.0D) {
                                    if (vgmRoundedValue > eqTypeSafeWt) {
                                        log("VgmRoundedValue " + vgmRoundedValue + "EqTypeSafeWt " + eqTypeSafeWt)
                                        appendToMessageCollector("Weight is greater than equipment type safe weight");
                                        log("Weight is greater than equipment type safe weight");
                                    } else {
                                        String roundedValue = convertKgtoLb(convertedVgmWt);
                                        long lbRoundedVal = Math.round(roundedValue.toDouble());
                                        activeUnit.setUnitFlexString12(lbRoundedVal.toString());
                                    }
                                }
                            } else {
                                log("Vgm ROunded Value " + vgmRoundedValue);
                                if (vgmRoundedValue > ctrSafeWt) {
                                    appendToMessageCollector("Weight is greater than safe weight");
                                    log("Weight is greater than safe weight");
                                } else {
                                    String roundedValue = convertKgtoLb(convertedVgmWt);
                                    long lbRoundedVal = Math.round(roundedValue.toDouble());
                                    activeUnit.setUnitFlexString12(lbRoundedVal.toString());
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
     * Method to Kilogram to convertPound
     *
     * @return
     */
    private String convertLbtoKg(String inVgmWt) {
        Double valueInLb = UnitUtils.convertTo(inVgmWt, MassUnitEnum.KILOGRAMS, MassUnitEnum.POUNDS);
        //log("valueInKg " + inVgmWt + " => valueInLb : " + String.valueOf(valueInLb));
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
            roundedValue = inVgmWt;
            return roundedValue;
        } else if ("LB".equals(inCtrGrossWt)) {
            log("LB value setting to flexfield");
            roundedValue = convertLbtoKg(inVgmWt);
            long lbRoundedVal = Math.round(roundedValue.toDouble());
            return lbRoundedVal.toString();
        }
        return roundedValue;
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