 * b) Click on + (Add) Button
47
 * c) Add as EDI_POST_INTERCEPTOR and code extension name as PashaUpdateVGMWeightToFlexField
48
 * d) Paste the groovy code and click on save
49
 *
50
 */
51
​
52
class PashaUpdateVGMWeightToFlexField extends AbstractEdiPostInterceptor {
53
​
54
    @Override
55
    public void beforeEdiPost(XmlObject inXmlTransactionDocument, Map inParams) {
56
        log("Inside PashaUpdateVGMWeightToFlexField :: START");
57
​
58
        Serializable batchGKey = inParams.get("BATCH_GKEY");
59
        EdiBatch ediBatch = (EdiBatch) HibernateApi.getInstance().load(EdiBatch.class, batchGKey);
60
​
61
        inParams.put(EdiConsts.SKIP_POSTER, Boolean.TRUE);
62
​
63
        PreadviseTransactionsDocument preadviseDocument = (PreadviseTransactionsDocument) inXmlTransactionDocument;
64
        PreadviseTransactionsDocument.PreadviseTransactions preadviseTransactions = preadviseDocument.getPreadviseTransactions();
65
        List<PreadviseTransactionDocument.PreadviseTransaction> list = preadviseTransactions.getPreadviseTransactionList();
66
​
67
        if (list.isEmpty()) {
68
            throw BizFailure.create("There is no transaction in the batch");
69
        }
70
        try {
71
            for (PreadviseTransactionDocument.PreadviseTransaction preadviseTransaction : list) {
72
                EdiContainer ctr = preadviseTransaction.getEdiContainer()
73
                if (ctr == null) {
74
                    appendToMessageCollector("File Does not contain container number");
75
                    log("File Does not contain container number");
76
                    ContextHelper.getThreadEdiPostingContext().throwIfAnyViolations();
77
                } else {
78
                    String ctrNbr = ctr.getContainerNbr()
79
                    log("Container Number " + ctrNbr);
80
                    if (ctrNbr == null) {
81
                        appendToMessageCollector("File Does not contain container number");
82
                        log("File Does not contain container number");
83
                    }
84
​
85
                    EdiBooking booking = preadviseTransaction.getEdiBooking();
86
                    if (booking == null) {
87
                        appendToMessageCollector("File Does not contain booking number");
88
                        log("File Does not consist booking details");
89
                    }
90
​
91
                    EdiFlexFields flexFields = preadviseTransaction.getEdiFlexFields();
92
​
93
                    Unit activeUnit = getUnit(ctrNbr);
94
​
95
                    EqBaseOrder bookingNbr = null;
96
                    log("Transaction Unit " + activeUnit);
97
                    if (activeUnit != null) {
98
                        bookingNbr = activeUnit.getDepartureOrder();
99
                    }
100
                    if (activeUnit == null) {
101
                        appendToMessageCollector("Unit does not exists");
102
                        log("Unit does not exists" + activeUnit);
103
                    }
104
​
105
                    EdiOperator ctrOperator = ctr.getContainerOperator();
