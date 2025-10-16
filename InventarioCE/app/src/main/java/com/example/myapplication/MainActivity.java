package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private enum Mode { IDLE, READ_ARMED, EDIT_ON_DETECT }

    private NfcAdapter nfcAdapter;
    private TextView tvEstado, tvSalida;
    private Button btnLeer, btnEscribir;

    private volatile Mode mode = Mode.IDLE;
    private volatile String lastReadValue = null; // para prellenar
    private volatile Tag pendingEditTag = null;   // tag detectado al que se escribirá

    private boolean isEmulator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvEstado  = findViewById(R.id.tvEstado);
        tvSalida  = findViewById(R.id.tvSalida);
        btnLeer   = findViewById(R.id.btnLeer);
        btnEscribir = findViewById(R.id.btnEscribir);

        isEmulator =
                android.os.Build.FINGERPRINT.contains("generic")
                        || android.os.Build.FINGERPRINT.startsWith("unknown")
                        || android.os.Build.MODEL.contains("Emulator")
                        || android.os.Build.MODEL.contains("Android SDK built for x86");

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            tvEstado.setText(isEmulator
                    ? "Modo emulador: no hay NFC (UI de demo activa)."
                    : "Este dispositivo no tiene NFC. Solo UI.");
        } else if (!nfcAdapter.isEnabled()) {
            tvEstado.setText("NFC desactivado. Actívalo en Ajustes para usar lectura/escritura.");
        } else {
            tvEstado.setText("Elige Leer o Escribir.");
        }

        // === LEER: arma lectura y muestra el valor leído ===
        btnLeer.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Simulación
                lastReadValue = "12345678";
                tvSalida.setText("Leído (simulado): " + lastReadValue);
                tvEstado.setText("Lectura simulada.");
                mode = Mode.IDLE;
            } else {
                mode = Mode.READ_ARMED;
                tvEstado.setText("Modo LECTURA armado: acerca el tag para leer.");
            }
        });

        // === ESCRIBIR: detectar tag -> abrir diálogo con valor actual -> grabar ===
        btnEscribir.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Simulación completa: "detecta" y edita/graba sin NFC
                String prefill = (lastReadValue == null) ? "" : lastReadValue;
                showEditDialog(prefill, value -> {
                    lastReadValue = value;
                    tvSalida.setText("Grabado (simulado): " + value);
                    tvEstado.setText("Escritura simulada.");
                });
            } else {
                mode = Mode.EDIT_ON_DETECT;
                tvEstado.setText("Acerca el tag para EDITAR su contenido.");
            }
        });
    }


    @Override
    protected void onResume() {
        super.onResume();
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            int flags =  NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F
                    | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK   // <-- evita que Android abra apps externas
                    | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS; // opcional
            nfcAdapter.enableReaderMode(this, this, flags, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    private void safeRunOnUi(Runnable r) {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            r.run();
        });
    }

    private volatile long lastWriteMs = 0;
    private static final long SQUELCH_MS = 1500;
    // ==== NFC callback (solo dispositivo real) ====
    @Override
    public void onTagDiscovered(Tag tag) {
        long now = System.currentTimeMillis();
        if (now - lastWriteMs < SQUELCH_MS) return;  // ignora relecturas inmediatas
        switch (mode) {
            case READ_ARMED: {
                Executors.newSingleThreadExecutor().execute(() -> {
                    // Solo MifareClassic sector 1 → número
                    if (MifareClassic.get(tag) != null) {
                        String numero = readOnlyNumberFromSector(tag, /*sector*/1, KEY_DEFAULT);
                        safeRunOnUi(() -> {
                            if (numero == null || numero.isEmpty()) {
                                tvSalida.setText("");  // no mostrar dump
                                tvEstado.setText("No se encontró número en el sector 1.");
                                lastReadValue = null;
                            } else {
                                lastReadValue = numero;
                                tvSalida.setText(numero);           // <-- SOLO el 97175
                                tvEstado.setText("Lectura OK.");
                            }
                            mode = Mode.IDLE;
                        });
                        return;
                    }

                    // Si no es Classic, podrías conservar tu lectura NDEF/Ultralight
                    safeRunOnUi(() -> {
                        tvSalida.setText("");
                        tvEstado.setText("Tag no MifareClassic.");
                        mode = Mode.IDLE;
                    });
                });
                break;
            }

            case EDIT_ON_DETECT: {
                pendingEditTag = tag;
                String techInfo = techsString(tag);
                safeRunOnUi(() -> tvSalida.setText(techInfo));

                if (MifareClassic.get(tag) != null) {
                    // Detect field (buscar números) en sector 1
                    Executors.newSingleThreadExecutor().execute(() -> {
                        FieldDetect fd = detectFieldInSector(tag, /*sectorIndex*/1, "\\d{3,}", KEY_DEFAULT);
                        if (fd == null) {
                            safeRunOnUi(() -> {
                                tvEstado.setText("No se encontró campo numérico en sector 1 (o no se pudo autenticar).");
                                mode = Mode.IDLE;
                                pendingEditTag = null;
                            });
                            return;
                        }

                        // Mostrar valor detectado en UI y abrir diálogo para editar
                        safeRunOnUi(() -> {
                            tvEstado.setText("Valor detectado: " + fd.value + ". Edita y confirma para sobrescribir.");
                            showEditDialog(fd.value, newValue -> {
                                // onConfirm: escribir
                                Executors.newSingleThreadExecutor().execute(() -> {
                                    WriteOutcome out = replaceFieldInSector(pendingEditTag, fd, newValue, KEY_DEFAULT);
                                    safeRunOnUi(() -> {
                                        tvEstado.setText(out.userMessage);
                                        if (out.ok) {
                                            tvSalida.setText("Campo actualizado: " + newValue);
                                            lastWriteMs = System.currentTimeMillis();
                                        } else {
                                            android.util.Log.w("MIFARE_WRITE", out.technicalDetail);
                                        }
                                        pendingEditTag = null;
                                        mode = Mode.IDLE;
                                    });
                                });
                            });
                        });
                    });
                    break;
                }
            }

            case IDLE:
            default:
                runOnUiThread(() -> toast("Elige Leer o Escribir primero."));
        }
    }

    static class FieldDetect {
        final String value;        // valor ASCII detectado (ej "97175")
        final int sectorIndex;     // sector donde se halló (ej 1)
        final int byteStart;       // start byte offset dentro de la zona de datos del sector (0..dataBytes-1)
        final int byteLength;      // longitud en bytes del match
        FieldDetect(String value, int sectorIndex, int byteStart, int byteLength) {
            this.value = value; this.sectorIndex = sectorIndex; this.byteStart = byteStart; this.byteLength = byteLength;
        }
    }

    private FieldDetect detectFieldInSector(Tag tag, int sectorIndex, String regex, byte[] key) {
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;
        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1; // exclude trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;
            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }
            String ascii = new String(all, StandardCharsets.UTF_8);
            // For searching, convert non-printables to dots so regex won't match them; but we want raw ascii bytes for positions.
            // We'll search on ASCII printable representation to find visible digits.
            String printable = toAsciiPrintable(all);
            Pattern pat = Pattern.compile(regex);
            Matcher m = pat.matcher(printable);
            if (m.find()) {
                int startPrintable = m.start();
                int length = m.end() - m.start();
                // Map printable index back to byte index: because toAsciiPrintable replaced non-printables with '.' but kept length same,
                // indices correspond to byte offsets.
                return new FieldDetect(m.group(), sectorIndex, startPrintable, length);
            } else {
                return null;
            }
        } catch (Exception e) {
            android.util.Log.w("MIFARE_DETECT", e);
            return null;
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    private WriteOutcome replaceFieldInSector(Tag tag, FieldDetect field, String newValue, byte[] key) {
        if (tag == null || field == null) return WriteOutcome.fail("Parámetros inválidos.", "null input");

        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return WriteOutcome.fail("Tag no soporta MifareClassic.", "mc==null");

        byte[] newBytes = (newValue == null) ? new byte[0] : newValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(field.sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(field.sectorIndex, key);
            if (!auth) return WriteOutcome.fail("No se pudo autenticar sector para escribir.", "auth failed sector=" + field.sectorIndex);

            int firstBlock = mc.sectorToBlock(field.sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(field.sectorIndex) - 1; // excluye trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            // Leer TODOS los bytes de datos del sector
            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // Calcular el span máximo disponible a partir de byteStart
            int start = field.byteStart;
            if (start < 0 || start >= all.length) {
                return WriteOutcome.fail("Posición de inicio fuera de rango.", "start=" + start + " total=" + all.length);
            }
            int maxSpan = computeNumericFieldSpan(all, start);
            if (maxSpan <= 0) {
                return WriteOutcome.fail("No hay espacio contiguo disponible para expandir el campo.", "maxSpan<=0");
            }

            // Construir reemplazo con truncado/padding según maxSpan
            int cap = Math.min(maxSpan, all.length - start);
            byte[] replacement = new byte[cap];
            java.util.Arrays.fill(replacement, (byte)0x20); // padding con espacios
            int copyLen = Math.min(cap, newBytes.length);
            System.arraycopy(newBytes, 0, replacement, 0, copyLen);

            // Escribir en el buffer 'all'
            System.arraycopy(replacement, 0, all, start, cap);

            // Volcar de regreso bloque por bloque (solo data blocks)
            for (int i = 0; i < dataBlocks; i++) {
                int blockIndex = firstBlock + i;
                byte[] blockData = new byte[MifareClassic.BLOCK_SIZE];
                System.arraycopy(all, i * MifareClassic.BLOCK_SIZE, blockData, 0, MifareClassic.BLOCK_SIZE);
                mc.writeBlock(blockIndex, blockData);
            }

            // Verificar la región escrita
            byte[] back = new byte[totalBytes];
            p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, back, p, b.length);
                p += b.length;
            }
            for (int i = 0; i < cap; i++) {
                if (back[start + i] != replacement[i]) {
                    return WriteOutcome.fail("Verificación falló (bytes distintos tras escribir).", "verify mismatch at +" + i);
                }
            }

            String note = (newBytes.length > cap)
                    ? " (truncado a " + cap + " bytes disponibles)"
                    : "";
            return WriteOutcome.ok("Campo actualizado a '" + newValue + "'" + note);

        } catch (java.io.IOException e) {
            return WriteOutcome.fail("Error E/S durante la escritura. Mantén el tag quieto.", "IOException: " + e.getMessage());
        } catch (Exception e) {
            return WriteOutcome.fail("Fallo inesperado al escribir.", "Exception: " + e.getMessage());
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    private int computeNumericFieldSpan(byte[] all, int start) {
        int i = start;
        while (i < all.length) {
            int v = all[i] & 0xFF;
            boolean isDigit = (v >= 0x30 && v <= 0x39);
            boolean isPad   = (v == 0x20 || v == 0x00);
            if (isDigit || isPad) {
                i++;
            } else {
                break;
            }
        }
        return i - start;
    }

    /** Lee los bloques de datos del sector dado (excluye trailer) y devuelve SOLO el primer grupo de dígitos (>=3). */
    private String readOnlyNumberFromSector(Tag tag, int sectorIndex, byte[] key) {
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;
        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1; // último es trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // Convertimos a “ASCII imprimible” para que el índice case 1:1 con bytes
            String ascii = toAsciiPrintable(all);
            Matcher m = Pattern.compile("\\d{3,}").matcher(ascii);
            return m.find() ? m.group() : null;

        } catch (Exception e) {
            android.util.Log.w("MIFARE_READ_NUM", e);
            return null;
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }


    // ---------- Utils -----
    private String toAsciiPrintable(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) {
            int v = x & 0xFF;
            // imprimibles básicos
            if (v >= 32 && v <= 126) sb.append((char) v);
            else sb.append('.'); // marca no-imprimibles
        }
        // opcional: recorta puntos finales para ver dónde termina el texto real
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '.') end--;
        return sb.substring(0, end);
    }


    // Resultado tipado
    static class WriteOutcome {
        final boolean ok;
        final String userMessage;
        final String technicalDetail;
        WriteOutcome(boolean ok, String userMessage, String technicalDetail) {
            this.ok = ok; this.userMessage = userMessage; this.technicalDetail = technicalDetail;
        }
        static WriteOutcome ok(String msg) { return new WriteOutcome(true, msg, ""); }
        static WriteOutcome fail(String userMsg, String tech) { return new WriteOutcome(false, userMsg, tech); }
    }

    private String techsString(Tag tag) {
        if (tag == null) return "(sin tag)";
        String[] techs = tag.getTechList(); // e.g. ["android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic"]
        StringBuilder sb = new StringBuilder("Techs: ");
        for (int i = 0; i < techs.length; i++) {
            String t = techs[i];
            int lastDot = t.lastIndexOf('.');
            sb.append(lastDot >= 0 ? t.substring(lastDot + 1) : t);
            if (i < techs.length - 1) sb.append(", ");
        }
        return sb.toString();
    }

    // ===== Diálogo de edición =====
    private interface ConfirmCallback { void onConfirm(String value); }

    private void showEditDialog(String prefill, ConfirmCallback cb) {
        final EditText input = new EditText(this);
        input.setHint("Número (ej. 88887777)");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(32) });
        if (prefill != null) input.setText(prefill);

        new AlertDialog.Builder(this)
                .setTitle("Editar número del tag")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (val.isEmpty()) {
                        toast("Ingresa un número.");
                        return;
                    }
                    cb.onConfirm(val);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    tvEstado.setText("Operación cancelada.");
                    // Si estábamos esperando escribir sobre un tag detectado:
                    pendingEditTag = null;
                    mode = Mode.IDLE;
                })
                .show();
    }
    private static final byte[] KEY_DEFAULT = new byte[]{
            (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
    };

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
