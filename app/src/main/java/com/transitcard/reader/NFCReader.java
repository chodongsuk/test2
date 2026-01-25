package com.transitcard.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NFCReader {
    private static final String TAG = "NFCReader";

    public TransitCardData readCard(Tag tag) {
        Log.d(TAG, "=== readCard: Starting card read ===");
        try {
            byte[] id = tag.getId();
            Log.d(TAG, "readCard: Card ID = " + bytesToHex(id));
            Log.d(TAG, "readCard: Card ID length = " + id.length + " bytes");

            // Log all available technologies
            String[] techList = tag.getTechList();
            Log.d(TAG, "readCard: Available technologies:");
            for (String tech : techList) {
                Log.d(TAG, "readCard:   - " + tech);
            }

            // Try MifareClassic first (EZL cards use this)
            MifareClassic mifareClassic = MifareClassic.get(tag);
            Log.d(TAG, "readCard: MifareClassic available = " + (mifareClassic != null));
            if (mifareClassic != null) {
                TransitCardData result = readMifareClassicCard(mifareClassic, id);
                if (result != null) {
                    return result;
                }
            }

            // Try IsoDep (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            Log.d(TAG, "readCard: IsoDep available = " + (isoDep != null));
            if (isoDep != null) {
                return readIsoDepCard(isoDep, id);
            }

            // Try NfcA
            NfcA nfcA = NfcA.get(tag);
            Log.d(TAG, "readCard: NfcA available = " + (nfcA != null));
            if (nfcA != null) {
                return readNfcACard(nfcA, id);
            }

            Log.w(TAG, "readCard: No supported NFC technology found");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "readCard: Error reading card", e);
            return null;
        }
    }

    private TransitCardData readIsoDepCard(IsoDep isoDep, byte[] cardId) {
        Log.d(TAG, "readIsoDepCard: Starting IsoDep card read");
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);
            Log.d(TAG, "readIsoDepCard: Connected to card, timeout=5000ms");
            Log.d(TAG, "readIsoDepCard: Max transceive length = " + isoDep.getMaxTransceiveLength());

            // Detect card type based on card ID and AID
            CardType cardType = detectCardType(cardId, isoDep);
            Log.i(TAG, "readIsoDepCard: Detected card type = " + cardType);

            CardParser parser = null;
            switch (cardType) {
                case TMONEY:
                    parser = new TMoneyParser();
                    break;
                case CASHBEE:
                    parser = new CashbeeParser();
                    break;
                case HANPAY:
                    parser = new HanpayParser();
                    break;
                case RAILPLUS:
                    parser = new RailplusParser();
                    break;
                case MPASS:
                    parser = new MPassParser();
                    break;
                case SEOUL_CITY_PASS:
                    parser = new SeoulCityPassParser();
                    break;
                case KOREA_TOUR_CARD:
                    parser = new KoreaTourCardParser();
                    break;
                case EZL:
                    parser = new EZLParser();
                    break;
            }

            TransitCardData result = null;
            if (parser != null) {
                Log.d(TAG, "readIsoDepCard: Using parser = " + parser.getClass().getSimpleName());
                result = parser.parse(isoDep, cardId);
                Log.d(TAG, "readIsoDepCard: Parse result = " + (result != null ? "success" : "null"));
            } else {
                Log.w(TAG, "readIsoDepCard: No parser available for card type " + cardType);
            }

            isoDep.close();
            Log.d(TAG, "readIsoDepCard: Connection closed");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "readIsoDepCard: Error reading IsoDep card", e);
            try {
                isoDep.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readNfcACard(NfcA nfcA, byte[] cardId) {
        try {
            nfcA.connect();

            // Create basic card data
            CardType cardType = detectCardTypeFromId(cardId);
            String cardNumber = bytesToHex(cardId);

            nfcA.close();

            return new TransitCardData(cardType, cardNumber, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error reading NfcA card", e);
            try {
                nfcA.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readMifareClassicCard(MifareClassic mifareClassic, byte[] cardId) {
        Log.d(TAG, "=== readMifareClassicCard: Starting Mifare Classic read ===");
        try {
            mifareClassic.connect();
            Log.d(TAG, "readMifareClassicCard: Connected to card");
            Log.d(TAG, "readMifareClassicCard: Card type = " + mifareClassic.getType());
            Log.d(TAG, "readMifareClassicCard: Sector count = " + mifareClassic.getSectorCount());
            Log.d(TAG, "readMifareClassicCard: Block count = " + mifareClassic.getBlockCount());
            Log.d(TAG, "readMifareClassicCard: Size = " + mifareClassic.getSize() + " bytes");

            int balance = 0;

            // EZL 카드는 특정 섹터에 잔액 정보가 저장됨
            // 일반적으로 섹터 2에 잔액이 있음 (블록 8, 9, 10)
            // 키는 보통 기본 키 또는 알려진 키 사용

            // 다양한 키 시도
            byte[] KEY_DEFAULT = MifareClassic.KEY_DEFAULT; // FF FF FF FF FF FF
            byte[] KEY_MIFARE_APPLICATION_DIRECTORY = MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY;
            byte[] KEY_NFC_FORUM = MifareClassic.KEY_NFC_FORUM;
            // 한국 교통카드에서 자주 사용되는 키
            byte[] KEY_KOREAN_TRANSIT = new byte[]{(byte)0xA0, (byte)0xA1, (byte)0xA2, (byte)0xA3, (byte)0xA4, (byte)0xA5};

            byte[][] keysToTry = {KEY_DEFAULT, KEY_MIFARE_APPLICATION_DIRECTORY, KEY_NFC_FORUM, KEY_KOREAN_TRANSIT};
            String[] keyNames = {"DEFAULT", "MAD", "NFC_FORUM", "KOREAN_TRANSIT"};

            // 섹터 2를 읽어 잔액 확인 시도
            int targetSector = 2;
            boolean authenticated = false;

            for (int k = 0; k < keysToTry.length && !authenticated; k++) {
                Log.d(TAG, "readMifareClassicCard: Trying key " + keyNames[k] + " for sector " + targetSector);
                try {
                    // Key A로 시도
                    if (mifareClassic.authenticateSectorWithKeyA(targetSector, keysToTry[k])) {
                        Log.i(TAG, "readMifareClassicCard: Authenticated sector " + targetSector + " with KeyA (" + keyNames[k] + ")");
                        authenticated = true;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "readMifareClassicCard: KeyA " + keyNames[k] + " failed: " + e.getMessage());
                }

                if (!authenticated) {
                    try {
                        // Key B로 시도
                        if (mifareClassic.authenticateSectorWithKeyB(targetSector, keysToTry[k])) {
                            Log.i(TAG, "readMifareClassicCard: Authenticated sector " + targetSector + " with KeyB (" + keyNames[k] + ")");
                            authenticated = true;
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "readMifareClassicCard: KeyB " + keyNames[k] + " failed: " + e.getMessage());
                    }
                }
            }

            if (authenticated) {
                // 섹터의 블록들 읽기
                int firstBlock = mifareClassic.sectorToBlock(targetSector);
                int blockCount = mifareClassic.getBlockCountInSector(targetSector);
                Log.d(TAG, "readMifareClassicCard: Reading sector " + targetSector + ", blocks " + firstBlock + " to " + (firstBlock + blockCount - 1));

                for (int i = 0; i < blockCount - 1; i++) { // 마지막 블록은 트레일러
                    int blockIndex = firstBlock + i;
                    try {
                        byte[] blockData = mifareClassic.readBlock(blockIndex);
                        Log.d(TAG, "readMifareClassicCard: Block " + blockIndex + " = " + bytesToHex(blockData));

                        // 잔액 파싱 시도 (첫 4바이트를 little-endian으로)
                        if (i == 0 && blockData.length >= 4) {
                            balance = ByteBuffer.wrap(blockData, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                            Log.d(TAG, "readMifareClassicCard: Parsed balance (LE) = " + balance);

                            // 음수면 big-endian으로 시도
                            if (balance < 0) {
                                balance = ByteBuffer.wrap(blockData, 0, 4).getInt();
                                Log.d(TAG, "readMifareClassicCard: Parsed balance (BE) = " + balance);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "readMifareClassicCard: Error reading block " + blockIndex, e);
                    }
                }
            } else {
                Log.w(TAG, "readMifareClassicCard: Could not authenticate any sector");

                // 인증 없이 모든 섹터 시도 (일부 카드는 공개 섹터가 있음)
                for (int sector = 0; sector < mifareClassic.getSectorCount(); sector++) {
                    for (int k = 0; k < keysToTry.length; k++) {
                        try {
                            if (mifareClassic.authenticateSectorWithKeyA(sector, keysToTry[k])) {
                                Log.i(TAG, "readMifareClassicCard: Found readable sector " + sector + " with " + keyNames[k]);
                                int firstBlock = mifareClassic.sectorToBlock(sector);
                                byte[] blockData = mifareClassic.readBlock(firstBlock);
                                Log.d(TAG, "readMifareClassicCard: Sector " + sector + " Block " + firstBlock + " = " + bytesToHex(blockData));
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            mifareClassic.close();
            Log.d(TAG, "readMifareClassicCard: Connection closed, balance = " + balance);

            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    balance
            );
        } catch (Exception e) {
            Log.e(TAG, "readMifareClassicCard: Error reading Mifare Classic card", e);
            try {
                mifareClassic.close();
            } catch (Exception ignored) {}
            return null;
        }
    }

    private CardType detectCardType(byte[] cardId, IsoDep isoDep) {
        Log.d(TAG, "detectCardType: Starting card type detection");
        Log.d(TAG, "detectCardType: Card ID = " + bytesToHex(cardId));

        // 다양한 한국 교통카드 AID 목록
        byte[][] aidList = {
                // T-money 계열
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x01},
                // Cashbee
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x02},
                // Rail+
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x03},
                // 한페이/원패스
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x04},
                // EZL 추정 AID
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x05},
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x06},
                // 추가 한국 교통카드 AID
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00},
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x30, (byte) 0x00, (byte) 0x01},
                // KFTC (금융결제원) AID
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x04, (byte) 0x52, (byte) 0x00, (byte) 0x01},
                // Visa Contactless
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x10, (byte) 0x10},
                // Mastercard Contactless
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x04, (byte) 0x10, (byte) 0x10},
        };

        String[] aidNames = {
                "T-money", "Cashbee", "Rail+", "Hanpay", "EZL-1", "EZL-2",
                "Korea-Transit-0", "Korea-Transit-Alt", "KFTC", "Visa", "Mastercard"
        };

        // 모든 AID 시도
        for (int i = 0; i < aidList.length; i++) {
            Log.d(TAG, "detectCardType: Trying " + aidNames[i] + " AID...");
            try {
                byte[] response = selectAID(isoDep, aidList[i]);
                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;
                    Log.d(TAG, "detectCardType: " + aidNames[i] + " response SW=" + String.format("%02X%02X", sw1, sw2));
                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Log.i(TAG, "detectCardType: Card detected with AID: " + aidNames[i]);
                        // AID에 따라 카드 타입 반환
                        switch (i) {
                            case 0: return CardType.TMONEY;
                            case 1: return CardType.CASHBEE;
                            case 2: return CardType.RAILPLUS;
                            case 3: return CardType.HANPAY;
                            case 4:
                            case 5: return CardType.EZL;
                            default:
                                // 알 수 없는 AID지만 성공하면 EZL로 시도
                                Log.i(TAG, "detectCardType: Unknown AID succeeded, trying as EZL");
                                return CardType.EZL;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "detectCardType: " + aidNames[i] + " failed: " + e.getMessage());
            }
        }

        // AID 선택 없이 직접 잔액 읽기 시도 (일부 카드는 AID 선택 없이 동작)
        Log.d(TAG, "detectCardType: Trying direct read without AID selection...");
        try {
            // T-money 잔액 조회 명령어 시도
            byte[] balanceCmd = new byte[]{(byte) 0x90, (byte) 0x4C, 0x00, 0x00, 0x04};
            Log.d(TAG, "detectCardType: Direct balance command = " + bytesToHex(balanceCmd));
            byte[] response = isoDep.transceive(balanceCmd);
            Log.d(TAG, "detectCardType: Direct balance response = " + bytesToHex(response));
            if (response != null && response.length >= 4) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: Direct read succeeded, treating as EZL");
                    return CardType.EZL;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: Direct read failed: " + e.getMessage());
        }

        // Fallback to ID-based detection
        Log.w(TAG, "detectCardType: No AID matched, falling back to ID-based detection");
        return detectCardTypeFromId(cardId);
    }

    private CardType detectTmoneyBasedCard(IsoDep isoDep, byte[] cardId) {
        // Seoul City Pass and Korea Tour Card use T-money platform
        // but have distinctive characteristics

        // This would need actual card testing to implement proper detection
        // For now, return UNKNOWN to default to TMONEY
        return CardType.UNKNOWN;
    }

    private CardType detectCardTypeFromId(byte[] cardId) {
        // This is a simplified detection based on common patterns
        // Real implementation would need more sophisticated detection
        return CardType.UNKNOWN;
    }

    private byte[] selectAID(IsoDep isoDep, byte[] aid) {
        byte[] selectCommand = new byte[6 + aid.length];
        selectCommand[0] = 0x00; // CLA
        selectCommand[1] = (byte) 0xA4; // INS (SELECT)
        selectCommand[2] = 0x04; // P1
        selectCommand[3] = 0x00; // P2
        selectCommand[4] = (byte) aid.length; // Lc
        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
        selectCommand[selectCommand.length - 1] = 0x00; // Le

        try {
            Log.d(TAG, "selectAID: Sending SELECT command for AID: " + bytesToHex(aid));
            Log.d(TAG, "selectAID: Command = " + bytesToHex(selectCommand));
            byte[] response = isoDep.transceive(selectCommand);
            Log.d(TAG, "selectAID: Response = " + bytesToHex(response));
            return response;
        } catch (Exception e) {
            Log.e(TAG, "selectAID: Error selecting AID " + bytesToHex(aid), e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
