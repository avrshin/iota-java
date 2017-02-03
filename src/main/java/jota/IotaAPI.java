package jota;

import jota.dto.response.*;
import jota.error.*;
import jota.model.*;
import jota.pow.ICurl;
import jota.pow.JCurl;
import jota.utils.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * IotaAPI Builder. Usage:
 * <p>
 * IotaApiProxy api = IotaApiProxy.Builder
 * .protocol("http")
 * .nodeAddress("localhost")
 * .port(12345)
 * .build();
 * <p>
 * GetNodeInfoResponse response = api.getNodeInfo();
 *
 * @author davassi
 */
public class IotaAPI extends IotaAPICore {

    private static final Logger log = LoggerFactory.getLogger(IotaAPI.class);
    private ICurl customCurl;

    protected IotaAPI(Builder builder) {
        super(builder);
        customCurl = builder.customCurl;
    }

    /**
     * Generates a new address from a seed and returns the remainderAddress.
     * This is either done deterministically, or by providing the index of the new remainderAddress
     *
     * @param seed      Tryte-encoded seed. It should be noted that this seed is not transferred
     * @param security  Security level to be used for the private key / address. Can be 1, 2 or 3
     * @param index     Optional (default null). Key index to start search from. If the index is provided, the generation of the address is not deterministic.
     * @param checksum  Optional (default false). Adds 9-tryte address checksum
     * @param total     Optional (default 1)Total number of addresses to generate
     * @param returnAll If true, it returns all addresses which were deterministically generated (until findTransactions returns null)
     * @return an array of strings with the specifed number of addresses
     */
    public GetNewAddressResponse getNewAddress(final String seed, int security, final int index, final boolean checksum, final int total, final boolean returnAll) throws InvalidSecurityLevelException {

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        StopWatch stopWatch = new StopWatch();

        List<String> allAddresses = new ArrayList<>();

        // If total number of addresses to generate is supplied, simply generate
        // and return the list of all addresses
        if (total != 0) {
            for (int i = index; i < index + total; i++) {
                allAddresses.add(IotaAPIUtils.newAddress(seed, security, i, checksum, customCurl));
            }
            return GetNewAddressResponse.create(allAddresses, stopWatch.getElapsedTimeMili());
        }
        // No total provided: Continue calling findTransactions to see if address was
        // already created if null, return list of addresses
        for (int i = index; ; i++) {

            final String newAddress = IotaAPIUtils.newAddress(seed, security, i, checksum, customCurl);
            final FindTransactionResponse response = findTransactionsByAddresses(newAddress);

            allAddresses.add(newAddress);
            if (response.getHashes().length == 0) {
                break;
            }
        }

        // If !returnAll return only the last address that was generated
        if (!returnAll) {

            //allAddresses = allAddresses.subList(allAddresses.size() - 2, allAddresses.size() - 1);
            allAddresses = allAddresses.subList(allAddresses.size() - 1, allAddresses.size());
        }
        return GetNewAddressResponse.create(allAddresses, stopWatch.getElapsedTimeMili());
    }

    /**
     * @param seed
     * @param start
     * @param end
     * @param inclusionStates
     * @param security
     * @param seed
     * @param seed
     * @returns Bundle
     **/
    public GetTransferResponse getTransfers(String seed, int security, Integer start, Integer end, Boolean inclusionStates) throws ArgumentException, InvalidBundleException, InvalidSignatureException, NoNodeInfoException, NoInclusionStatesExcpection, InvalidSecurityLevelException {
        StopWatch stopWatch = new StopWatch();
        // validate & if needed pad seed
        if ((seed = InputValidator.validateSeed(seed)) == null) {
            throw new IllegalStateException("Invalid Seed");
        }

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        start = start != null ? 0 : start;

        if (start > end || end > (start + 500)) {
            throw new ArgumentException();
        }
        StopWatch sw = new StopWatch();

        System.out.println("GetTransfer started");
        GetNewAddressResponse gnr = getNewAddress(seed, security, start, false, end == null ? end - start : end, true);
        if (gnr != null && gnr.getAddresses() != null) {
            System.out.println("GetTransfers after getNewAddresses " + sw.getElapsedTimeMili() + " ms");
            Bundle[] bundles = bundlesFromAddresses(gnr.getAddresses().toArray(new String[gnr.getAddresses().size()]), inclusionStates);
            System.out.println("GetTransfers after bundlesFromAddresses " + sw.getElapsedTimeMili() + " ms");
            return GetTransferResponse.create(bundles, stopWatch.getElapsedTimeMili());
        }
        return GetTransferResponse.create(new Bundle[]{}, stopWatch.getElapsedTimeMili());
    }

    public Bundle[] bundlesFromAddresses(String[] addresses, final Boolean inclusionStates) throws ArgumentException, InvalidBundleException, InvalidSignatureException, NoNodeInfoException, NoInclusionStatesExcpection {

        List<Transaction> trxs = findTransactionObjects(addresses);
        // set of tail transactions
        List<String> tailTransactions = new ArrayList<>();
        List<String> nonTailBundleHashes = new ArrayList<>();

        for (Transaction trx : trxs) {
            // Sort tail and nonTails
            if (Long.parseLong(trx.getCurrentIndex()) == 0) {
                tailTransactions.add(trx.getHash());
            } else {
                if (nonTailBundleHashes.indexOf(trx.getBundle()) == -1) {
                    nonTailBundleHashes.add(trx.getBundle());
                }
            }
        }

        List<Transaction> bundleObjects = findTransactionObjectsByBundle(nonTailBundleHashes.toArray(new String[nonTailBundleHashes.size()]));
        for (Transaction trx : bundleObjects) {
            // Sort tail and nonTails
            if (Long.parseLong(trx.getCurrentIndex()) == 0) {
                if (tailTransactions.indexOf(trx.getHash()) == -1) {
                    tailTransactions.add(trx.getHash());
                }
            }
        }

        final List<Bundle> finalBundles = new ArrayList<>();
        final String[] tailTxArray = tailTransactions.toArray(new String[tailTransactions.size()]);

        // If inclusionStates, get the confirmation status
        // of the tail transactions, and thus the bundles
        GetInclusionStateResponse gisr = null;
        if (tailTxArray != null && tailTxArray.length != 0 && inclusionStates) {
            try {
                gisr = getLatestInclusion(tailTxArray);
            } catch (IllegalAccessError ignored) {
                throw new NoInclusionStatesExcpection();
            }
            if (gisr == null || gisr.getStates() == null || gisr.getStates().length == 0) {
                throw new NoInclusionStatesExcpection();
            }
        }
        final GetInclusionStateResponse finalInclusionStates = gisr;
        Parallel.For(Arrays.asList(tailTxArray),
                new Parallel.Operation<String>() {
                    public void perform(String param) {

                        try {
                            GetBundleResponse bundleResponse = getBundle(param);
                            Bundle gbr = new Bundle(bundleResponse.getTransactions(), bundleResponse.getTransactions().size());
                            if (gbr != null && gbr.getTransactions() != null) {
                                if (inclusionStates) {
                                    boolean thisInclusion = finalInclusionStates.getStates()[Arrays.asList(tailTxArray).indexOf(param)];
                                    for (Transaction t : gbr.getTransactions()) {
                                        t.setPersistence(thisInclusion);
                                    }
                                }
                                finalBundles.add(gbr);
                            }
                            // If error returned from getBundle, simply ignore it because the bundle was most likely incorrect
                        } catch (InvalidBundleException | ArgumentException | InvalidSignatureException e) {
                            log.warn("GetBundleError: ", e);
                        }
                    }
                });

        Collections.sort(finalBundles);
        Bundle[] returnValue = new Bundle[finalBundles.size()];
        for (int i = 0; i < finalBundles.size(); i++) {
            returnValue[i] = new Bundle(finalBundles.get(i).getTransactions(), finalBundles.get(i).getTransactions().size());
        }
        return returnValue;
    }

    /**
     * @param trytes
     * @return a StoreTransactionsResponse
     */
    public StoreTransactionsResponse broadcastAndStore(final String... trytes) throws BroadcastAndStoreException {

        try {
            broadcastTransactions(trytes);
        } catch (Exception e) {
            log.error("Impossible to broadcastAndStore, aborting.", e);
            throw new BroadcastAndStoreException();
        }
        return storeTransactions(trytes);
    }

    /**
     * Facade method: Gets transactions to approve, attaches to Tangle, broadcasts and stores
     *
     * @param {array} trytes
     * @param {int}   depth
     * @param {int}   minWeightMagnitude
     * @return
     */
    public List<Transaction> sendTrytes(final String[] trytes, final int depth, final int minWeightMagnitude) {
        final GetTransactionsToApproveResponse txs = getTransactionsToApprove(depth);

        // attach to tangle - do pow
        final GetAttachToTangleResponse res = attachToTangle(txs.getTrunkTransaction(), txs.getBranchTransaction(), minWeightMagnitude, trytes);

        try {
            broadcastAndStore(res.getTrytes());
        } catch (BroadcastAndStoreException e) {
            return new ArrayList<>();
        }

        final List<Transaction> trx = new ArrayList<>();

        for (final String tryte : Arrays.asList(res.getTrytes())) {
            trx.add(new Transaction(tryte, customCurl));
        }
        return trx;
    }

    /**
     * Wrapper function for getTrytes and transactionObjects
     * gets the trytes and transaction object from a list of transaction hashes
     *
     * @param {array} hashes
     * @return
     * @method getTransactionsObjects
     * @returns {function} callback
     * @returns {object} success
     **/
    public List<Transaction> getTransactionsObjects(String[] hashes) {

        if (!InputValidator.isArrayOfHashes(hashes)) {
            throw new IllegalStateException("Not an Array of Hashes: " + Arrays.toString(hashes));
        }

        final GetTrytesResponse trytesResponse = getTrytes(hashes);

        final List<Transaction> trxs = new ArrayList<>();

        for (final String tryte : trytesResponse.getTrytes()) {
            trxs.add(new Transaction(tryte, customCurl));
        }
        return trxs;
    }

    /**
     * Wrapper function for findTransactions, getTrytes and transactionObjects
     * Returns the transactionObject of a transaction hash. The input can be a valid
     * findTransactions input
     *
     * @param {object} input
     * @method getTransactionsObjects
     * @returns {function} callback
     * @returns {object} success
     **/
    public List<Transaction> findTransactionObjects(String[] input) {
        FindTransactionResponse ftr = findTransactions(input, null, null, null);
        if (ftr == null || ftr.getHashes() == null)
            return new ArrayList<>();
        // get the transaction objects of the transactions
        return getTransactionsObjects(ftr.getHashes());
    }

    /**
     * Wrapper function for findTransactions, getTrytes and transactionObjects
     * Returns the transactionObject of a transaction hash. The input can be a valid
     * findTransactions input
     *
     * @param {object} input
     * @method getTransactionsObjects
     * @returns {function} callback
     * @returns {object} success
     **/
    public List<Transaction> findTransactionObjectsByBundle(String[] input) {
        FindTransactionResponse ftr = findTransactions(null, null, null, input);
        if (ftr == null || ftr.getHashes() == null)
            return new ArrayList<>();

        // get the transaction objects of the transactions
        return getTransactionsObjects(ftr.getHashes());
    }

    /**
     * Prepares transfer by generating bundle, finding and signing inputs
     *
     * @param seed
     * @param security
     * @param transfers
     * @param remainder
     * @param inputs
     * @param security
     * @returns {array} trytes Returns bundle trytes
     **/
    public List<String> prepareTransfers(String seed, int security, final List<Transfer> transfers, String remainder, List<Input> inputs) throws NotEnoughBalanceException, InvalidSecurityLevelException {

        // Input validation of transfers object
        if (!InputValidator.isTransfersCollectionCorrect(transfers)) {
            throw new IllegalStateException("Invalid Transfer");
        }

        // validate & if needed pad seed
        if ((seed = InputValidator.validateSeed(seed)) == null) {
            throw new IllegalStateException("Invalid Seed");
        }

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        // Create a new bundle
        final Bundle bundle = new Bundle();
        final List<String> signatureFragments = new ArrayList<>();

        long totalValue = 0;
        String tag = "";

        //  Iterate over all transfers, get totalValue
        //  and prepare the signatureFragments, message and tag
        for (final Transfer transfer : transfers) {

            // If address with checksum then remove checksum
            if (Checksum.isAddressWithChecksum(transfer.getAddress()))
                transfer.setAddress(Checksum.removeChecksum(transfer.getAddress()));

            int signatureMessageLength = 1;

            // If message longer than 2187 trytes, increase signatureMessageLength (add 2nd transaction)
            if (transfer.getMessage().length() > 2187) {

                // Get total length, message / maxLength (2187 trytes)
                signatureMessageLength += Math.floor(transfer.getMessage().length() / 2187);

                String msgCopy = transfer.getMessage();

                // While there is still a message, copy it
                while (!msgCopy.isEmpty()) {

                    String fragment = StringUtils.substring(msgCopy, 0, 2187);
                    msgCopy = StringUtils.substring(msgCopy, 2187, msgCopy.length());

                    // Pad remainder of fragment

                    fragment = StringUtils.rightPad(fragment, 2187, '9');

                    signatureFragments.add(fragment);
                }
            } else {
                // Else, get single fragment with 2187 of 9's trytes
                String fragment = StringUtils.substring(transfer.getMessage(), 0, 2187);

                fragment = StringUtils.rightPad(fragment, 2187, '9');

                signatureFragments.add(fragment);
            }

            // get current timestamp in seconds
            long timestamp = (long) Math.floor(Calendar.getInstance().getTimeInMillis() / 1000);

            // If no tag defined, get 27 tryte tag.
            tag = transfer.getTag().isEmpty() ? "999999999999999999999999999" : transfer.getTag();

            // Pad for required 27 tryte length
            tag = StringUtils.rightPad(tag, 27, '9');

            // Add first entry to the bundle
            bundle.addEntry(signatureMessageLength, transfer.getAddress(), transfer.getValue(), tag, timestamp);
            // Sum up total value
            totalValue += transfer.getValue();
        }

        // Get inputs if we are sending tokens
        if (totalValue != 0) {

            //  Case 1: user provided inputs
            //  Validate the inputs by calling getBalances
            if (inputs != null && !inputs.isEmpty()) {

                // Get list if addresses of the provided inputs
                List<String> inputsAddresses = new ArrayList<>();
                for (final Input i : inputs) {
                    inputsAddresses.add(i.getAddress());
                }

                GetBalancesResponse balancesResponse = getBalances(100, inputsAddresses);
                String[] balances = balancesResponse.getBalances();

                List<Input> confirmedInputs = new ArrayList<>();
                int totalBalance = 0;
                int i = 0;
                for (String balance : balances) {
                    long thisBalance = Integer.parseInt(balance);

                    // If input has balance, add it to confirmedInputs
                    if (thisBalance > 0) {
                        totalBalance += thisBalance;
                        Input inputEl = inputs.get(i++);
                        inputEl.setBalance(thisBalance);
                        confirmedInputs.add(inputEl);

                        // if we've already reached the intended input value, break out of loop
                        if (totalBalance >= totalValue) {
                            log.info("Total balance already reached ");
                            break;
                        }
                    }
                }

                // Return not enough balance error
                if (totalValue > totalBalance) {
                    throw new IllegalStateException("Not enough balance");
                }

                return addRemainder(seed, security, confirmedInputs, bundle, tag, totalValue, null, signatureFragments);
            }

            //  Case 2: Get inputs deterministically
            //
            //  If no inputs provided, derive the addresses from the seed and
            //  confirm that the inputs exceed the threshold
            else {

                @SuppressWarnings("unchecked") GetBalancesAndFormatResponse newinputs = getInputs(seed, security, 0, 0, totalValue);
                // If inputs with enough balance
                return addRemainder(seed, security, newinputs.getInput(), bundle, tag, totalValue, null, signatureFragments);
            }
        } else {

            // If no input required, don't sign and simply finalize the bundle
            bundle.finalize(customCurl);
            bundle.addTrytes(signatureFragments);

            List<Transaction> trxb = bundle.getTransactions();
            List<String> bundleTrytes = new ArrayList<>();

            for (Transaction trx : trxb) {
                bundleTrytes.add(trx.toTrytes());
            }
            Collections.reverse(bundleTrytes);
            return bundleTrytes;
        }
    }

    /**
     * Gets the inputs of a seed
     *
     * @param seed
     * @param security  security secuirty level of private key / seed
     * @param start     start Starting key index
     * @param end       end Ending key index
     * @param threshold threshold Min balance required
     **/
    public GetBalancesAndFormatResponse getInputs(String seed, int security, int start, int end, long threshold) throws InvalidSecurityLevelException {
        StopWatch stopWatch = new StopWatch();
        // validate the seed
        if (!InputValidator.isTrytes(seed, 0)) {
            throw new IllegalStateException("Invalid Seed");
        }

        // validate & if needed pad seed
        if ((seed = InputValidator.validateSeed(seed)) == null) {
            throw new IllegalStateException("Invalid Seed");
        }

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        // If start value bigger than end, return error
        // or if difference between end and start is bigger than 500 keys
        if (start > end || end > (start + 500)) {
            throw new IllegalStateException("Invalid inputs provided");
        }

        //  Case 1: start and end
        //
        //  If start and end is defined by the user, simply iterate through the keys
        //  and call getBalances
        if (end != 0) {

            List<String> allAddresses = new ArrayList<>();

            for (int i = start; i < end; i++) {

                String address = IotaAPIUtils.newAddress(seed, security, i, false, customCurl);
                allAddresses.add(address);
            }

            return getBalanceAndFormat(allAddresses, threshold, start, end, stopWatch, security);
        }
        //  Case 2: iterate till threshold || end
        //
        //  Either start from index: 0 or start (if defined) until threshold is reached.
        //  Calls getNewAddress and deterministically generates and returns all addresses
        //  We then do getBalance, format the output and return it
        else {
            final GetNewAddressResponse res = getNewAddress(seed, security, start, false, 0, true);
            return getBalanceAndFormat(res.getAddresses(), threshold, start, end, stopWatch, security);
        }
    }

    //  Calls getBalances and formats the output
    //  returns the final inputsObject then
    public GetBalancesAndFormatResponse getBalanceAndFormat(final List<String> addresses, long threshold, int start, int end, StopWatch stopWatch, int security) throws InvalidSecurityLevelException {

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        GetBalancesResponse getBalancesResponse = getBalances(100, addresses);
        List<String> balances = Arrays.asList(getBalancesResponse.getBalances());

        // If threshold defined, keep track of whether reached or not
        // else set default to true
        boolean thresholdReached = threshold == 0;
        int i = -1;

        List<Input> inputs = new ArrayList<>();
        long totalBalance = 0;

        for (String address : addresses) {

            long balance = Long.parseLong(balances.get(++i));

            if (balance > 0) {
                final Input newEntry = new Input(address, balance, start + i, security);

                inputs.add(newEntry);
                // Increase totalBalance of all aggregated inputs
                totalBalance += balance;

                if (!thresholdReached && totalBalance >= threshold) {
                    thresholdReached = true;
                    break;
                }
            }
        }

        if (thresholdReached) {
            return GetBalancesAndFormatResponse.create(inputs, totalBalance, stopWatch.getElapsedTimeMili());
        }
        throw new IllegalStateException("Not enough balance");
    }

    /**
     * Gets the associated bundle transactions of a single transaction
     * Does validation of signatures, total sum as well as bundle order
     *
     * @param {string} transaction Hash of a tail transaction
     * @method getBundle
     * @returns {list} bundle Transaction objects
     **/
    public GetBundleResponse getBundle(String transaction) throws ArgumentException, InvalidBundleException, InvalidSignatureException {
        StopWatch stopWatch = new StopWatch();

        Bundle bundle = traverseBundle(transaction, null, new Bundle());
        if (bundle == null) {
            throw new ArgumentException("Unknown Bundle");
        }

        long totalSum = 0;
        String bundleHash = bundle.getTransactions().get(0).getBundle();

        ICurl curl = new JCurl();
        curl.reset();

        List<Signature> signaturesToValidate = new ArrayList<>();

        for (int i = 0; i < bundle.getTransactions().size(); i++) {
            Transaction trx = bundle.getTransactions().get(i);
            Long bundleValue = Long.parseLong(trx.getValue());
            totalSum += bundleValue;

            if (i != Integer.parseInt(bundle.getTransactions().get(i).getCurrentIndex())) {
                throw new ArgumentException("Invalid Bundle");
            }

            String trxTrytes = trx.toTrytes().substring(2187, 2187 + 162);
            //System.out.println("Bundlesize "+bundle.getTransactions().size()+" "+trxTrytes);
            // Absorb bundle hash + value + timestamp + lastIndex + currentIndex trytes.
            curl.absorb(Converter.trits(trxTrytes));
            // Check if input transaction
            if (bundleValue < 0) {
                String address = trx.getAddress();
                Signature sig = new Signature();
                sig.setAddress(address);
                sig.getSignatureFragments().add(trx.getSignatureFragments());

                // Find the subsequent txs with the remaining signature fragment
                for (int y = i; y < bundle.getTransactions().size() - 1; y++) {
                    Transaction newBundleTx = bundle.getTransactions().get(i + 1);

                    // Check if new tx is part of the signature fragment
                    if (newBundleTx.getAddress().equals(address) && Long.parseLong(newBundleTx.getValue()) == 0) {
                        if (sig.getSignatureFragments().indexOf(newBundleTx.getSignatureFragments()) == -1)
                            sig.getSignatureFragments().add(newBundleTx.getSignatureFragments());
                    }
                }
                signaturesToValidate.add(sig);
            }
        }

        // Check for total sum, if not equal 0 return error
        if (totalSum != 0) throw new InvalidBundleException("Invalid Bundle Sum");
        int[] bundleFromTrxs = new int[243];
        curl.squeeze(bundleFromTrxs);
        String bundleFromTxString = Converter.trytes(bundleFromTrxs);

        // Check if bundle hash is the same as returned by tx object
        if (!bundleFromTxString.equals(bundleHash)) throw new InvalidBundleException("Invalid Bundle Hash");
        // Last tx in the bundle should have currentIndex === lastIndex
        bundle.setLength(bundle.getTransactions().size());
        if (!bundle.getTransactions().get(bundle.getLength() - 1).getCurrentIndex().equals(bundle.getTransactions().get(bundle.getLength() - 1).getLastIndex()))
            throw new InvalidBundleException("Invalid Bundle");

        // Validate the signatures
        for (Signature aSignaturesToValidate : signaturesToValidate) {
            String[] signatureFragments = aSignaturesToValidate.getSignatureFragments().toArray(new String[aSignaturesToValidate.getSignatureFragments().size()]);
            String address = aSignaturesToValidate.getAddress();
            boolean isValidSignature = new Signing().validateSignatures(address, signatureFragments, bundleHash);

            if (!isValidSignature) throw new InvalidSignatureException();
        }

        return GetBundleResponse.create(bundle.getTransactions(), stopWatch.getElapsedTimeMili());
    }

    /**
     * Replays a transfer by doing Proof of Work again
     *
     * @param {string}   tail
     * @param {int}      depth
     * @param {int}      minWeightMagnitude
     * @param {function} callback
     * @method replayBundle
     * @returns {object} analyzed Transaction objects
     **/
    public ReplayBundleResponse replayBundle(String transaction, int depth, int minWeightMagnitude) throws InvalidBundleException, ArgumentException, InvalidSignatureException {
        StopWatch stopWatch = new StopWatch();

        List<String> bundleTrytes = new ArrayList<>();

        GetBundleResponse bundleResponse = getBundle(transaction);
        Bundle bundle = new Bundle(bundleResponse.getTransactions(), bundleResponse.getTransactions().size());
        for (Transaction trx : bundle.getTransactions()) {

            bundleTrytes.add(trx.toTrytes());
        }

        List<Transaction> trxs = sendTrytes(bundleTrytes.toArray(new String[bundleTrytes.size()]), depth, minWeightMagnitude);

        Boolean[] successful = new Boolean[trxs.size()];

        for (int i = 0; i < trxs.size(); i++) {

            final FindTransactionResponse response = findTransactionsByBundles(trxs.get(i).getBundle());


            successful[i] = response.getHashes().length != 0;
        }

        return ReplayBundleResponse.create(successful, stopWatch.getElapsedTimeMili());
    }

    /**
     * Wrapper function for getNodeInfo and getInclusionStates
     *
     * @param {array} hashes
     * @method getLatestInclusion
     * @returns {function} callback
     * @returns {array} state
     **/
    public GetInclusionStateResponse getLatestInclusion(String[] hashes) throws NoNodeInfoException {
        GetNodeInfoResponse getNodeInfoResponse = getNodeInfo();
        if (getNodeInfoResponse == null) throw new NoNodeInfoException();

        String[] latestMilestone = {getNodeInfoResponse.getLatestSolidSubtangleMilestone()};

        return getInclusionStates(hashes, latestMilestone);
    }

    public SendTransferResponse sendTransfer(String seed, int security, int depth, int minWeightMagnitude, final List<Transfer> transfers, Input[] inputs, String address) throws NotEnoughBalanceException, InvalidSecurityLevelException {

        if (security < 1 || security > 3) {
            throw new InvalidSecurityLevelException();
        }

        StopWatch stopWatch = new StopWatch();

        List<String> trytes = prepareTransfers(seed, security, transfers, address, inputs == null ? null : Arrays.asList(inputs));
        List<Transaction> trxs = sendTrytes(trytes.toArray(new String[trytes.size()]), depth, minWeightMagnitude);

        Boolean[] successful = new Boolean[trxs.size()];

        for (int i = 0; i < trxs.size(); i++) {

            final FindTransactionResponse response = findTransactionsByBundles(trxs.get(i).getBundle());

            successful[i] = response.getHashes().length != 0;
        }

        return SendTransferResponse.create(successful, stopWatch.getElapsedTimeMili());
    }

    /**
     * Basically traverse the Bundle by going down the trunkTransactions until
     * the bundle hash of the transaction is no longer the same. In case the input
     * transaction hash is not a tail, we return an error.
     *
     * @param {string} trunkTx Hash of a trunk or a tail transaction  of a bundle
     * @param {string} bundleHash
     * @param {array}  bundle List of bundles to be populated
     * @method traverseBundle
     * @returns {array} bundle Transaction objects
     **/
    public Bundle traverseBundle(String trunkTx, String bundleHash, Bundle bundle) throws ArgumentException {
        GetTrytesResponse gtr = getTrytes(trunkTx);

        if (gtr != null) {

            if (gtr.getTrytes().length == 0) {
                throw new ArgumentException("Bundle transactions not visible");
            }

            Transaction trx = new Transaction(gtr.getTrytes()[0], customCurl);
            if (trx == null || trx.getBundle() == null) {
                throw new ArgumentException("Invalid trytes, could not create object");
            }
            // If first transaction to search is not a tail, return error
            if (bundleHash == null && Integer.parseInt(trx.getCurrentIndex()) != 0) {
                throw new ArgumentException("Invalid tail transaction supplied.");
            }
            // If no bundle hash, define it
            if (bundleHash == null) {
                bundleHash = trx.getBundle();
            }
            // If different bundle hash, return with bundle
            if (!bundleHash.equals(trx.getBundle())) {
                return bundle;
            }
            // If only one bundle element, return
            if (Integer.parseInt(trx.getLastIndex()) == 0 && Integer.parseInt(trx.getCurrentIndex()) == 0) {
                return new Bundle(Collections.singletonList(trx), 1);
            }
            // Define new trunkTransaction for search
            trunkTx = trx.getTrunkTransaction();
            // Add transaction object to bundle
            bundle.getTransactions().add(trx);

            // Continue traversing with new trunkTx
            return traverseBundle(trunkTx, bundleHash, bundle);
        } else {
            throw new ArgumentException("Get Trytes Response was null");
        }
    }

    public String findTailTransactionHash(String hash) throws ArgumentException {
        GetTrytesResponse gtr = getTrytes(hash);

        if (gtr == null) throw new ArgumentException("Invalid hash");

        if (gtr.getTrytes().length == 0) {
            throw new ArgumentException("Bundle transactions not visible");
        }

        Transaction trx = new Transaction(gtr.getTrytes()[0], customCurl);
        if (trx == null || trx.getBundle() == null) {
            throw new ArgumentException("Invalid trytes, could not create object");
        }
        if (Integer.parseInt(trx.getCurrentIndex()) == 0) return trx.getHash();
        else return findTailTransactionHash(trx.getBundle());
    }

    public List<String> addRemainder(final String seed,
                                     final int security,
                                     final List<Input> inputs,
                                     final Bundle bundle,
                                     final String tag,
                                     final long totalValue,
                                     final String remainderAddress,
                                     final List<String> signatureFragments) throws NotEnoughBalanceException, InvalidSecurityLevelException {

        long totalTransferValue = totalValue;
        for (int i = 0; i < inputs.size(); i++) {
            long thisBalance = inputs.get(i).getBalance();
            long toSubtract = 0 - thisBalance;
            long timestamp = (long) Math.floor(Calendar.getInstance().getTimeInMillis() / 1000);

            // Add input as bundle entry
            bundle.addEntry(security, inputs.get(i).getAddress(), toSubtract, tag, timestamp);
            // If there is a remainder value
            // Add extra output to send remaining funds to

            if (thisBalance >= totalTransferValue) {
                long remainder = thisBalance - totalTransferValue;

                // If user has provided remainder address
                // Use it to send remaining funds to
                if (remainder > 0 && remainderAddress != null) {
                    // Remainder bundle entry
                    bundle.addEntry(1, remainderAddress, remainder, tag, timestamp);
                    // Final function for signing inputs
                    return IotaAPIUtils.signInputsAndReturn(seed, inputs, bundle, signatureFragments, customCurl);
                } else if (remainder > 0) {
                    // Generate a new Address by calling getNewAddress

                    GetNewAddressResponse res = getNewAddress(seed, security, 0, false, 0, false);
                    // Remainder bundle entry
                    bundle.addEntry(1, res.getAddresses().get(0), remainder, tag, timestamp);

                    // Final function for signing inputs
                    return IotaAPIUtils.signInputsAndReturn(seed, inputs, bundle, signatureFragments, customCurl);
                } else {
                    // If there is no remainder, do not add transaction to bundle
                    // simply sign and return
                    return IotaAPIUtils.signInputsAndReturn(seed, inputs, bundle, signatureFragments, customCurl);
                }

                // If multiple inputs provided, subtract the totalTransferValue by
                // the inputs balance
            } else {
                totalTransferValue -= thisBalance;
            }
        }
        throw new NotEnoughBalanceException();
    }

    public static class Builder extends IotaAPICore.Builder<Builder> {
        private ICurl customCurl;

        public Builder withCustomCurl(ICurl curl) {
            customCurl = curl;
            return this;
        }

        public IotaAPI build() {
            super.build();
            return new IotaAPI(this);
        }
    }
}