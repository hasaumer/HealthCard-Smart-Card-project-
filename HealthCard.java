import javacard.framework.*;
import javacard.security.*;

public class HealthCard extends Applet {

    // --- Constants & Commands ---
    final static byte CLA_HEALTH = (byte) 0x90;

    // Instructions
    final static byte INS_VERIFY_PIN     = (byte) 0x20;
    final static byte INS_GET_STATUS     = (byte) 0x10;
    final static byte INS_REGISTER_CARD  = (byte) 0x15;
    final static byte INS_GET_EMERGENCY  = (byte) 0x30;
    final static byte INS_GET_INSURANCE  = (byte) 0x40;
    final static byte INS_ADD_RECORD     = (byte) 0x50;
    final static byte INS_GET_HISTORY    = (byte) 0x60;

    // Status Words
    final static short SW_WRONG_PIN = (short) 0x6300;
    final static short SW_NOT_REGISTERED = (short) 0x6401;
    final static short SW_ALREADY_ACTIVE = (short) 0x6402;
    final static short SW_SECURITY_NOT_SATISFIED = (short) 0x6982;

    // --- Data Structures ---
    private byte[] emergencyProfile;
    private static final byte PROFILE_SIZE = 31;

    private byte[] insuranceData;
    private static final byte INSURANCE_SIZE = 12;

    private static final byte RECORD_SIZE = 8;
    private static final byte MAX_RECORDS = 5;
    private byte[] medicalHistory;
    private byte historyIndex;

    // --- Security & State ---
    private OwnerPIN userPin;
    private OwnerPIN adminPin;
    private static final byte PIN_LENGTH = 4;

    private byte cardState; // 0 = BLANK, 1 = ACTIVE

    protected HealthCard() {
        // 1. Allocate Memory
        emergencyProfile = new byte[PROFILE_SIZE];
        insuranceData = new byte[INSURANCE_SIZE];
        medicalHistory = new byte[(short)(RECORD_SIZE * MAX_RECORDS)];

        // 2. Initialize PINs
        // FIX: Use ASCII '9' (0x39) instead of integer 9 (0x09) to match keyboard input
        byte[] adminDefault = {(byte)'9', (byte)'9', (byte)'9', (byte)'9'};
        adminPin = new OwnerPIN((byte)3, PIN_LENGTH);
        adminPin.update(adminDefault, (short)0, PIN_LENGTH);

        userPin = new OwnerPIN((byte)3, PIN_LENGTH);

        cardState = 0;
        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new HealthCard();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) return;

        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != CLA_HEALTH) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_GET_STATUS:
                getStatus(apdu);
                break;
            case INS_REGISTER_CARD:
                registerCard(apdu);
                break;
            case INS_VERIFY_PIN:
                verifyPin(apdu);
                break;
            case INS_GET_EMERGENCY:
                getEmergencyProfile(apdu);
                break;
            case INS_GET_INSURANCE:
                getInsurance(apdu);
                break;
            case INS_ADD_RECORD:
                addMedicalRecord(apdu);
                break;
            case INS_GET_HISTORY:
                getHistory(apdu);
                break;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    private void getStatus(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        buffer[0] = cardState;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    private void registerCard(APDU apdu) {
        // 1. Security: Must be logged in as Admin
        if (!adminPin.isValidated()) {
            ISOException.throwIt(SW_SECURITY_NOT_SATISFIED);
        }

        if (cardState != 0) {
            ISOException.throwIt(SW_ALREADY_ACTIVE);
        }

        byte[] buffer = apdu.getBuffer();
        short byteRead = apdu.setIncomingAndReceive();
        if (byteRead != (4 + 20 + 1 + 10 + 2 + 10)) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short offset = ISO7816.OFFSET_CDATA;

        // Set User PIN
        userPin.update(buffer, offset, PIN_LENGTH);
        offset += 4;

        // Set Name
        Util.arrayCopy(buffer, offset, emergencyProfile, (short)0, (short)20);
        offset += 20;

        // Set Blood Type
        emergencyProfile[20] = buffer[offset];
        offset += 1;

        // Set Insurance Policy
        Util.arrayCopy(buffer, offset, insuranceData, (short)0, (short)10);
        offset += 10;

        // Set Expiry Year
        Util.arrayCopy(buffer, offset, insuranceData, (short)10, (short)2);
        offset += 2;

        // Set Allergies
        Util.arrayCopy(buffer, offset, emergencyProfile, (short)21, (short)10);

        cardState = 1;
        adminPin.reset();
    }

    // --- FIX: Check BOTH Admin and User PIN ---
    private void verifyPin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        apdu.setIncomingAndReceive();

        // Try User PIN
        if (userPin.check(buffer, ISO7816.OFFSET_CDATA, PIN_LENGTH)) {
            return; // Success
        }

        // Try Admin PIN
        if (adminPin.check(buffer, ISO7816.OFFSET_CDATA, PIN_LENGTH)) {
            return; // Success
        }

        // If both failed
        ISOException.throwIt(SW_WRONG_PIN);
    }

    private void getEmergencyProfile(APDU apdu) {
        if (cardState == 0) ISOException.throwIt(SW_NOT_REGISTERED);

        byte[] buffer = apdu.getBuffer();
        Util.arrayCopy(emergencyProfile, (short)0, buffer, (short)0, PROFILE_SIZE);
        apdu.setOutgoingAndSend((short) 0, PROFILE_SIZE);
    }

    private void getInsurance(APDU apdu) {
        if (cardState == 0) ISOException.throwIt(SW_NOT_REGISTERED);
        if (!userPin.isValidated()) ISOException.throwIt(SW_SECURITY_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        Util.arrayCopy(insuranceData, (short)0, buffer, (short)0, INSURANCE_SIZE);
        apdu.setOutgoingAndSend((short) 0, INSURANCE_SIZE);
    }

    private void addMedicalRecord(APDU apdu) {
        if (cardState == 0) ISOException.throwIt(SW_NOT_REGISTERED);
        if (!userPin.isValidated()) ISOException.throwIt(SW_SECURITY_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        apdu.setIncomingAndReceive();

        short offset = (short)(historyIndex * RECORD_SIZE);
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, medicalHistory, offset, RECORD_SIZE);

        historyIndex++;
        if (historyIndex == MAX_RECORDS) historyIndex = 0;
    }

    private void getHistory(APDU apdu) {
        if (cardState == 0) ISOException.throwIt(SW_NOT_REGISTERED);
        if (!userPin.isValidated()) ISOException.throwIt(SW_SECURITY_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short totalSize = (short)(RECORD_SIZE * MAX_RECORDS);
        Util.arrayCopy(medicalHistory, (short)0, buffer, (short)0, totalSize);
        apdu.setOutgoingAndSend((short) 0, totalSize);
    }
}