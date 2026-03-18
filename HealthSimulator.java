import com.licel.jcardsim.base.Simulator;
import com.licel.jcardsim.base.SimulatorRuntime;
import javacard.framework.AID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HealthSimulator {

    private static final Logger LOGGER = Logger.getLogger(HealthSimulator.class.getName());
    private static final byte[] APPLET_AID_BYTES = {
            (byte) 0x90, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
    };

    private static Simulator simulator;

    public static void main(String[] args) {
        try {
            // 1. Initialize Simulator
            simulator = new Simulator(new SimulatorRuntime());
            AID appletAID = new AID(APPLET_AID_BYTES, (short) 0, (byte) APPLET_AID_BYTES.length);
            simulator.installApplet(appletAID, HealthCard.class);
            simulator.selectApplet(appletAID);

            System.out.println("========================================");
            System.out.println("    REAL HEALTH CARD SYSTEM SIMULATION  ");
            System.out.println("========================================");

            Scanner scanner = new Scanner(System.in);

            // 2. Check Card State (Real Detection Logic)
            boolean isActive = checkCardStatus();

            if (!isActive) {
                System.out.println("\n>>> SMART CARD DETECTED: BLANK (Unissued)");
                System.out.println(">>> Initiating Registration Protocol...");
                performRegistration(scanner);
            } else {
                System.out.println("\n>>> SMART CARD DETECTED: ACTIVE");
                System.out.println(">>> Ready for User Operations.");
            }

            // Main Loop
            while (true) {
                // Re-check status in case we just registered
                isActive = checkCardStatus();
                if (!isActive) {
                    System.out.println("Card is still blank. Please restart to retry registration.");
                    return;
                }

                System.out.println("\n--- MAIN MENU ---");
                System.out.println("1. View Emergency Profile (Public Access)");
                System.out.println("2. Login (User/Staff Verification)");
                System.out.println("3. View Insurance Details (Secured)");
                System.out.println("4. Add Medical Record (Doctor Mode)");
                System.out.println("5. View Medical History (Secured)");
                System.out.println("0. Exit");
                System.out.print("Choice: ");

                String choice = scanner.nextLine();

                try {
                    switch (choice) {
                        case "1": viewEmergencyProfile(); break;
                        case "2": performUserLogin(scanner); break;
                        case "3": viewInsurance(); break;
                        case "4": addMedicalRecord(scanner); break;
                        case "5": viewHistory(); break;
                        case "0": System.out.println("Goodbye."); return;
                        default: System.out.println("Invalid choice.");
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "System Error", e);
                    System.out.println("Error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- SYSTEM OPERATIONS ---

    private static boolean checkCardStatus() {
        CommandAPDU cmd = new CommandAPDU(0x90, 0x10, 0x00, 0x00);
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));
        if (response.getSW() == 0x9000 && response.getData().length > 0) {
            return response.getData()[0] == 1; // 1 = Active, 0 = Blank
        }
        return false;
    }

    private static void performRegistration(Scanner scanner) {
        try {
            System.out.println("\n--- ADMIN AUTHENTICATION REQUIRED ---");
            System.out.print("Enter Admin PIN (Default: 9999): ");
            String adminPin = scanner.nextLine();

            // 1. Verify Admin PIN
            CommandAPDU verifyCmd = new CommandAPDU(0x90, 0x20, 0x00, 0x00, adminPin.getBytes());
            ResponseAPDU verifyResp = new ResponseAPDU(simulator.transmitCommand(verifyCmd.getBytes()));

            if (verifyResp.getSW() != 0x9000) {
                System.out.println("Admin Authentication Failed. Cannot Register.");
                return;
            }
            System.out.println("Admin Verified. Proceeding with Card Personalization...");

            // 2. Collect Data
            System.out.print("Set User PIN (4 digits): ");
            String userPin = scanner.nextLine();
            if (userPin.length() != 4) { System.out.println("Invalid PIN length."); return; }

            System.out.print("Enter Patient Name (Max 20 chars): ");
            String name = padRight(scanner.nextLine(), 20);

            System.out.print("Enter Blood Type (1=A,2=B,3=O,4=AB): ");
            byte blood = Byte.parseByte(scanner.nextLine());

            System.out.print("Enter Insurance Policy (Max 10 chars): ");
            String policy = padRight(scanner.nextLine(), 10);

            System.out.print("Enter Expiry Year (e.g. 2025): ");
            short year = Short.parseShort(scanner.nextLine());

            System.out.print("Enter Allergies (Max 10 chars): ");
            String allergy = padRight(scanner.nextLine(), 10);

            // 3. Build Payload
            // [UserPIN(4) | Name(20) | Blood(1) | Policy(10) | Year(2) | Allergies(10)]
            byte[] data = new byte[4 + 20 + 1 + 10 + 2 + 10];
            int off = 0;

            System.arraycopy(userPin.getBytes(), 0, data, off, 4); off += 4;
            System.arraycopy(name.getBytes(), 0, data, off, 20); off += 20;
            data[off] = blood; off += 1;
            System.arraycopy(policy.getBytes(), 0, data, off, 10); off += 10;
            data[off] = (byte)(year >> 8); data[off+1] = (byte)year; off += 2;
            System.arraycopy(allergy.getBytes(), 0, data, off, 10);

            // 4. Send Registration Command
            CommandAPDU regCmd = new CommandAPDU(0x90, 0x15, 0x00, 0x00, data);
            ResponseAPDU regResp = new ResponseAPDU(simulator.transmitCommand(regCmd.getBytes()));

            if (regResp.getSW() == 0x9000) {
                System.out.println("\n>>> CARD REGISTRATION SUCCESSFUL <<<");
                System.out.println("Card is now ACTIVE and ready for use.");
            } else {
                System.out.println("Registration Failed. Error Code: " + Integer.toHexString(regResp.getSW()));
            }

        } catch (Exception e) {
            System.out.println("Registration Error: " + e.getMessage());
        }
    }

    private static void performUserLogin(Scanner scanner) {
        System.out.print("Enter User PIN: ");
        String pin = scanner.nextLine();
        CommandAPDU cmd = new CommandAPDU(0x90, 0x20, 0x00, 0x00, pin.getBytes());
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));
        if (response.getSW() == 0x9000) System.out.println(">>> USER LOGIN SUCCESSFUL <<<");
        else System.out.println(">>> LOGIN FAILED (Incorrect PIN) <<<");
    }

    private static void viewEmergencyProfile() {
        CommandAPDU cmd = new CommandAPDU(0x90, 0x30, 0x00, 0x00);
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));

        if (response.getSW() == 0x6401) { System.out.println("Card not registered."); return; }

        if (response.getSW() == 0x9000) {
            byte[] d = response.getData();
            String name = new String(d, 0, 20).trim();
            String allergy = new String(d, 21, 10).trim();
            String blood = (d[20] == 1) ? "A" : (d[20] == 2) ? "B" : (d[20] == 3) ? "O" : "AB";

            System.out.println("\n--- EMERGENCY PROFILE (Public) ---");
            System.out.println("Patient Name: " + name);
            System.out.println("Blood Type  : " + blood);
            System.out.println("Allergies   : " + allergy);
        }
    }

    private static void viewInsurance() {
        CommandAPDU cmd = new CommandAPDU(0x90, 0x40, 0x00, 0x00);
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));
        if (response.getSW() == 0x6982) { System.out.println("Access Denied: Please Login First."); return; }

        if (response.getSW() == 0x9000) {
            byte[] d = response.getData();
            String policy = new String(d, 0, 10).trim();
            short year = (short)((d[10] << 8) | (d[11] & 0xFF));
            System.out.println("\n--- INSURANCE DATA ---");
            System.out.println("Policy No: " + policy);
            System.out.println("Expires  : " + year);
        }
    }

    private static void addMedicalRecord(Scanner scanner) {
        System.out.print("Enter Diagnosis Code (e.g., FLU, INJ): ");
        String diag = scanner.nextLine().toUpperCase();
        while(diag.length() < 4) diag += " ";

        byte[] data = new byte[8];
        // Year 2024
        data[0] = 0x07; data[1] = (byte)0xE8;
        System.arraycopy(diag.getBytes(), 0, data, 2, 4);

        CommandAPDU cmd = new CommandAPDU(0x90, 0x50, 0x00, 0x00, data);
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));
        if (response.getSW() == 0x6982) System.out.println("Access Denied: Please Login First.");
        else if (response.getSW() == 0x9000) System.out.println("Medical Record Added Successfully.");
    }

    private static void viewHistory() {
        CommandAPDU cmd = new CommandAPDU(0x90, 0x60, 0x00, 0x00);
        ResponseAPDU response = new ResponseAPDU(simulator.transmitCommand(cmd.getBytes()));
        if (response.getSW() == 0x6982) { System.out.println("Access Denied: Please Login First."); return; }

        System.out.println("\n--- MEDICAL HISTORY ---");
        byte[] d = response.getData();
        boolean found = false;
        for (int i = 0; i < 5; i++) {
            int off = i * 8;
            short year = (short)((d[off] << 8) | (d[off+1] & 0xFF));
            if (year == 0) continue;
            found = true;
            String diag = new String(d, off + 2, 4).trim();
            System.out.println("Year: " + year + " | Code: " + diag);
        }
        if (!found) System.out.println("No records found.");
    }

    // Helper to pad strings for fixed-size smart card fields
    private static String padRight(String s, int n) {
        while (s.length() < n) s += " ";
        return s.substring(0, n);
    }
}