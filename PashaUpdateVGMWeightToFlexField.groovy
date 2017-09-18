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
import com.navis.argo.business.model.GeneralReference
import com.navis.argo.business.reference.EquipType
import com.navis.argo.business.reference.Equipment
import com.navis.argo.business.reference.LineOperator
import com.navis.edi.business.edimodel.EdiConsts
import com.navis.edi.business.entity.EdiSession
import com.navis.external.edi.entity.AbstractEdiPostInterceptor
import com.navis.framework.business.Roastery
import com.navis.framework.business.atoms.MassUnitEnum
import com.navis.framework.persistence.HibernateApi
import com.navis.framework.util.BizFailure
import com.navis.framework.util.message.MessageCollectorUtils
import com.navis.framework.util.unit.UnitUtils
import com.navis.inventory.business.api.UnitFinder
import com.navis.inventory.business.units.EqBaseOrder
import com.navis.inventory.business.units.Unit
import org.apache.xmlbeans.XmlObject

/**
 * This Edi Post Interceptor is written to update the flex field "UnitFlexString12" with VGM Weight.
 * If the weight is given in LB's it will update the same, if the weight is given in KG's value will be updated by
 * multiplying with 2.20462.
 *
 * This interceptor will skip the poster
 */

/*
 *
 * @Author <a href="mailto:peswendra@weservetech.com">Eswendra Reddy</a>, 08/Sep/2017
 *
 * Requirements : This groovy is used to update the flex field "UnitFlexString12" with VGM Weight.
 * If the weight is given in LB's it will update the same, if the weight is given in KG's value will be updated by
 * multiplying with 2.20462.
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
        Serializable sessionGKey = (Serializable) inParams.get(EdiConsts.SESSION_GKEY);
        log("Current EDI Session Gkey :: $sessionGKey")
        EdiSession ediSession = (EdiSession) HibernateApi.getInstance().load(EdiSession.class, sessionGKey);
        LineOperator sessionLineOperator;
        if (ediSession.getEdisessTradingPartner().getEdiptnrBusinessUnit() != null) {
            sessionLineOperator = LineOperator.resolveLineOprFromScopedBizUnit(ediSession.getEdisessTradingPartner().getEdiptnrBusinessUnit());
            log("Current EDI Session Line Operator :: $sessionLineOperator")
        }

        inParams.put(EdiConsts.SKIP_POSTER, Boolean.TRUE);
        PreadviseTransactionsDocument preAdviseDocument = (PreadviseTransactionsDocument) inXmlTransactionDocument;
        PreadviseTransactionsDocument.PreadviseTransactions preAdviseTransactions = preAdviseDocument.getPreadviseTransactions();
        List<PreadviseTransactionDocument.PreadviseTransaction> list = preAdviseTransactions.getPreadviseTransactionList();
        if (list.isEmpty()) {
            throw BizFailure.create("There is no transaction in the batch");
        }
        try {
            for (PreadviseTransactionDocument.PreadviseTransaction preAdviseTransaction : list) {
                Unit activeUnit
                if (canProceedToUpdate(activeUnit, preAdviseTransaction, sessionLineOperator)) {
                    log("Active Unit :: " + activeUnit);
                    activeUnit = getUnit(preAdviseTransaction.getEdiContainer().getContainerNbr())
                    EdiContainer ctr = preAdviseTransaction.getEdiContainer()
                    Equipment equipment = activeUnit.getUnitEquipment();
                    log("Current Equipment :: " + equipment);
                    String ctrGrossWt = ctr.getContainerGrossWtUnit();

                    Double ctrTareWt = 0.0D;
                    ctrTareWt = equipment.getEqTareWeightKg();

                    Double ctrSafeWt = 0.0D;
                    ctrSafeWt = equipment.getEqSafeWeightKg();

                    EquipType eqType = equipment.getEqEquipType();

                    String tareWtLbVal = convertKGtoLB(ctrTareWt.toString());
                    long tareWtRoundedVal = Math.round(tareWtLbVal.toDouble());

                    if (FreightKindEnum.MTY.equals(activeUnit.getUnitFreightKind())) {
                        log("Freight Kind is Empty, updating Equipment Tare Wt" + equipment.getEqTareWeightKg());
                        if (ctrTareWt == 0.0D) {
                            Double eqTypeTareeWt = eqType.getTareWeightKg();
                            if (eqTypeTareeWt == 0.0D) {
                                EdiFlexFields flexFields = preAdviseTransaction.getEdiFlexFields();
                                if (flexFields == null) {
                                    appendToMessageCollector("There is no Weight to update");
                                    log("There is no Weight to update");
                                } else {
                                    String VgmWt = flexFields.getUnitFlexString12();
                                    if (VgmWt == null) {
                                        appendToMessageCollector("There is no Weight to update");
                                        log("There is no Weight to update");
                                    }
                                    //convert VGM field to KG based on ctrGrossWtUnit if it is in LB's for comparision with fields such
                                    // as Equipment Tare Wt, Safe Wt, Equipment Type Safe Wt and Tare Wt, because by default fields
                                    //above fields will come here in KG's.
                                    String convertedVgmWt = weightConversionForVGM(ctrGrossWt, VgmWt);

                                    //convert VGM weight from KG to LB inorder to update to flex field
                                    String roundedValue = convertKGtoLB(convertedVgmWt);
                                    long lbRoundedVal = Math.round(roundedValue.toDouble());
                                    activeUnit.setUnitFlexString12(lbRoundedVal.toString());
                                }
                            } else {
                                String eqTypeTareeWtLbVal = convertKGtoLB(eqTypeTareeWt.toString());
                                long eqTypeTareeWtRoundedVal = Math.round(eqTypeTareeWtLbVal.toDouble());
                                activeUnit.setUnitFlexString12(eqTypeTareeWtRoundedVal.toString());
                            }
                        } else {
                            activeUnit.setUnitFlexString12(tareWtRoundedVal.toString());
                        }
                    } else if (FreightKindEnum.FCL.equals(activeUnit.getUnitFreightKind()) || FreightKindEnum.LCL.equals(activeUnit.getUnitFreightKind())) {
                        log("Inside FCL/LCL condition");
                        EdiFlexFields flexFields = preAdviseTransaction.getEdiFlexFields();
                        if (flexFields == null) {
                            appendToMessageCollector("File Does not contain proper weight");
                            log("File Does not contain proper weight");
                        } else {
                            String VgmWt = flexFields.getUnitFlexString12();
                            if (VgmWt == null) {
                                appendToMessageCollector("File Does not contain proper weight");
                                log("File Does not contain proper weight");
                            }

                            //convert VGM field to KG based on ctrGrossWtUnit if it is in LB's for comparision with fields such
                            // as Equipment Tare Wt, Safe Wt, Equipment Type Safe Wt and Tare Wt, because by default fields
                            //above fields will come here in KG's.
                            String convertedVgmWt = weightConversionForVGM(ctrGrossWt, VgmWt);
                            Double vgmRoundedValue = convertedVgmWt.toDouble();

                            //convert VGM weight from KG to LB inorder to update to flex field
                            String roundedValue = convertKGtoLB(convertedVgmWt);
                            long lbRoundedVal = Math.round(roundedValue.toDouble());

                            //Tare Wt comparision
                            if (ctrTareWt == 0.0D) {
                                Double eqTypeTareWt = eqType.getTareWeightKg();
                                if (eqTypeTareWt == 0.0D) {
                                } else {
                                    if (vgmRoundedValue < eqTypeTareWt) {
                                        appendToMessageCollector("Weight is less Equipment Type tare weight");
                                        log("Weight is less Equipment Type tare weight");
                                    }
                                }
                            } else if (vgmRoundedValue < ctrTareWt) {
                                appendToMessageCollector("Weight is less tare weight");
                                log("Weight is less tare weight");
                            }

                            //Safe Wt comparision
                            if (ctrSafeWt == 0.0D) {
                                Double eqTypeSafeWt = 0.0D;
                                eqTypeSafeWt = eqType.getEqtypSafeWeightKg();

                                if (eqTypeSafeWt == 0.0D) {
                                    activeUnit.setUnitFlexString12(lbRoundedVal.toString());
                                } else if (eqTypeSafeWt > 0.0D) {
                                    if (vgmRoundedValue > eqTypeSafeWt) {
                                        log("VgmRoundedValue " + vgmRoundedValue + "EqTypeSafeWt " + eqTypeSafeWt)
                                        appendToMessageCollector("Weight is greater than equipment type safe weight");
                                        log("Weight is greater than equipment type safe weight");
                                    } else {
                                        activeUnit.setUnitFlexString12(lbRoundedVal.toString());
                                    }
                                }
                            } else {
                                log("Vgm Rounded Value " + vgmRoundedValue);
                                if (vgmRoundedValue > ctrSafeWt) {
                                    appendToMessageCollector("Weight is greater than safe weight");
                                    log("Weight is greater than safe weight");
                                } else {
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

    private boolean canProceedToUpdate(Unit inUnit, PreadviseTransactionDocument.PreadviseTransaction preAdviseTransaction, LineOperator inLineOperator) {
        EdiContainer ctr = preAdviseTransaction.getEdiContainer();
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
            if (inUnit.getUnitLineOperator() != null && inLineOperator != null) {
                log("Unit Line Operator :: " + inUnit.getUnitLineOperator() + " and session Line Operator :: " + inLineOperator)
                if (!(inUnit.getUnitLineOperator().equals(inLineOperator))) {
                    GeneralReference lineOpGenRef = GeneralReference.findUniqueEntryById(LINE_OP_GEN_REF_ID, inLineOperator.getBzuId())
                    log("Line Operator Gen reference :: " + lineOpGenRef)
                    if (lineOpGenRef != null) {
                        if (allRefValuesEmpty(lineOpGenRef)) {
                            appendToMessageCollector("Line Operator mismatch with Unit");
                            return false
                        } else if (!matchesAnyRefValue(lineOpGenRef, inUnit.getUnitLineOperator().getBzuId())) {
                            appendToMessageCollector("Line Operator mismatch with Unit");
                            return false
                        }
                    } else {
                        appendToMessageCollector("Line Operator mismatch with Unit");
                        return false
                    }
                }
            } else {
                appendToMessageCollector("Required fields (Unit Line Operator / Session Line Operator) missing)");
            }
            EqBaseOrder unitBooking = inUnit.getDepartureOrder();
            if (unitBooking == null) {
                appendToMessageCollector("No booking associated with Unit");
                return false
            }
            EdiBooking ediBooking = preAdviseTransaction.getEdiBooking();
            if (ediBooking == null) {
                appendToMessageCollector("File Does not contain booking number");
                return false;
            }
            if (!unitBooking.getEqboNbr().equals(ediBooking.getBookingNbr())) {
                appendToMessageCollector("Booking mismatch with Unit");
                return false
            }

            String vesId = null;
            EdiCarrierVisit vessel = preAdviseTransaction.getEdiOutboundVisit();
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

    private boolean allRefValuesEmpty(GeneralReference generalReference) {
        if (generalReference.getRefValue1() == null && generalReference.getRefValue2() == null && generalReference.getRefValue3() == null
                && generalReference.getRefValue4() == null && generalReference.getRefValue5() == null && generalReference.getRefValue6() == null) {
            return true
        }
        return false
    }

    private boolean matchesAnyRefValue(GeneralReference generalReference, String inUnitLineOpId) {
        if (generalReference.getRefValue1(inUnitLineOpId) || generalReference.getRefValue2(inUnitLineOpId) || generalReference.getRefValue3(inUnitLineOpId)
                || generalReference.getRefValue4(inUnitLineOpId) || generalReference.getRefValue5(inUnitLineOpId) || generalReference.getRefValue6(inUnitLineOpId)) {
            return true
        }
        return false
    }
/**
 * To get the active export unit for the provided container number
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
    private String convertKGtoLB(String inVgmWt) {
        Double valueInLb = UnitUtils.convertTo(inVgmWt, MassUnitEnum.POUNDS, MassUnitEnum.KILOGRAMS);
        log("valueInKg " + inVgmWt + " => valueInLb : " + String.valueOf(valueInLb));
        return String.valueOf(valueInLb);
    }

    /**
     * Method to Kilogram to convertPound
     *
     * @return
     */
    private static String convertLBtoKG(String inVgmWt) {
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
    private String weightConversionForVGM(String inCtrGrossWt, String inVgmWt) {
        String roundedValue = new String();
        if ("KG".equals(inCtrGrossWt)) {
            log("KG value setting to flex field");
            roundedValue = inVgmWt;
            return roundedValue;
        } else if ("LB".equals(inCtrGrossWt)) {
            log("LB value setting to flex field");
            roundedValue = convertLBtoKG(inVgmWt);
            long lbRoundedVal = Math.round(roundedValue.toDouble());
            return lbRoundedVal.toString();
        }
        return roundedValue;
    }

    /**
     * Message Collector
     * @param inMessage
     */
    private static void appendToMessageCollector(String inMessage) {
        MessageCollectorUtils.getMessageCollector().appendMessage(BizFailure
                .create(ArgoPropertyKeys.INFO, null, inMessage));
    }

    private final String LINE_OP_GEN_REF_ID = "FLAT_FILE_LINE_OP";
}