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

import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    /**
     * Representa el modo actual de la pantalla:
     *  - IDLE: sin operación activa
     *  - READ_ARMED: esperando un tag para leer
     *  - EDIT_ON_DETECT: esperando un tag para editar/escribir
     */
    private enum Mode { IDLE, READ_ARMED, EDIT_ON_DETECT }

    // --------------------------------------------------------------------------------------------
    // Atributos (Campos/Propiedades)
    // --------------------------------------------------------------------------------------------

    /** Adaptador del sistema para interactuar con el hardware NFC (activar modo lector, etc.) */
    private NfcAdapter nfcAdapter;

    /** Texto en UI para mostrar el estado general (instrucciones/errores/progreso). */
    private TextView tvEstado;

    /** Texto en UI para mostrar el resultado principal (por ejemplo el número leído o info del tag). */
    private TextView tvSalida;

    /** Botón para iniciar el flujo de lectura. */
    private Button btnLeer;

    /** Botón para iniciar el flujo de escritura/edición. */
    private Button btnEscribir;

    /** Modo operativo actual de la Activity (idle, leer, editar). */
    private volatile Mode mode = Mode.IDLE;

    /** Último valor leído desde el tag, usado para prellenar el diálogo de edición. */
    private volatile String lastReadValue = null;

    /** Último tag detectado pendiente de escritura en modo EDIT_ON_DETECT. */
    private volatile Tag pendingEditTag = null;

    /** Bandera para detectar si la app corre en emulador y habilitar comportamientos simulados. */
    private boolean isEmulator;

    /** Momento (ms) del último write exitoso; se usa para filtrar lecturas/escrituras duplicadas seguidas. */
    private volatile long lastWriteMs = 0;

    /** Ventana de tiempo (ms) para ignorar múltiples callbacks consecutivos tras una escritura. */
    private static final long SQUELCH_MS = 1500;

    // --------------------------------------------------------------------------------------------
    // Interfaz interna para la confirmación del diálogo
    // --------------------------------------------------------------------------------------------
    /**
     * Descripción: Contrato para recibir el valor confirmado desde el diálogo de edición.
     */
    private interface ConfirmCallback { void onConfirm(String value); }

    // --------------------------------------------------------------------------------------------
    // Utilitario: Toast corto
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: s (String) - mensaje a mostrar.
     * Salidas: Ninguna.
     * Descripción: Muestra un Toast corto con el texto proporcionado.
     */
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // --------------------------------------------------------------------------------------------
    // Ciclo de vida: onCreate
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: savedInstanceState (Bundle) - estado previo si la Activity fue recreada.
     * Salidas: Ninguna (efecto sobre la UI y estado interno).
     * Descripción: Inicializa la UI, detecta capacidades NFC, configura listeners de botones y
     *              establece el modo inicial de la pantalla.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [Sección] Inflado de layout y referencias a vistas
        setContentView(R.layout.activity_main);
        tvEstado  = findViewById(R.id.tvEstado);
        tvSalida  = findViewById(R.id.tvSalida);
        btnLeer   = findViewById(R.id.btnLeer);
        btnEscribir = findViewById(R.id.btnEscribir);

        // [Sección] Detección de entorno (emulador vs dispositivo real)
        isEmulator =
                android.os.Build.FINGERPRINT.contains("generic")
                        || android.os.Build.FINGERPRINT.startsWith("unknown")
                        || android.os.Build.MODEL.contains("Emulator")
                        || android.os.Build.MODEL.contains("Android SDK built for x86");

        // [Sección] Inicialización de NFC y mensaje de estado
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

        // [Sección] Listener botón 'Leer': prepara el modo lectura o simula en emulador
        btnLeer.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Modo simulado: no hay NFC real
                lastReadValue = "12345678";
                tvSalida.setText("Leído (simulado): " + lastReadValue);
                tvEstado.setText("Lectura simulada.");
                mode = Mode.IDLE;
            } else {
                // Armar modo lectura para reaccionar al próximo tag
                mode = Mode.READ_ARMED;
                tvEstado.setText("Modo LECTURA armado: acerca el tag para leer.");
            }
        });

        // [Sección] Listener botón 'Escribir': prepara el modo edición o simula en emulador
        btnEscribir.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Modo simulado: abrir diálogo y "guardar"
                String prefill = (lastReadValue == null) ? "" : lastReadValue;
                showEditDialog(prefill, value -> {
                    lastReadValue = value;
                    tvSalida.setText("Grabado (simulado): " + value);
                    tvEstado.setText("Escritura simulada.");
                });
            } else {
                // Armar modo edición: la app pedirá tag y luego abrirá diálogo
                mode = Mode.EDIT_ON_DETECT;
                tvEstado.setText("Acerca el tag para EDITAR su contenido.");
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Ciclo de vida: onResume
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: Ninguna.
     * Salidas: Ninguna (efecto en sistema NFC).
     * Descripción: Habilita el modo lector NFC cuando la Activity pasa a primer plano.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // [Sección] Reobtención del adaptador (defensivo)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        // [Sección] Habilitar modo lector si hay NFC
        if (nfcAdapter != null) {
            int flags =  NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F
                    | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                    | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
            nfcAdapter.enableReaderMode(this, this, flags, null);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Ciclo de vida: onPause
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: Ninguna.
     * Salidas: Ninguna (efecto en sistema NFC).
     * Descripción: Deshabilita el modo lector NFC cuando la Activity sale de primer plano.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // [Sección] Deshabilitar modo lector para ahorrar energía y evitar callbacks en background
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Utilitario de UI seguro
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: r (Runnable) - acción que se ejecutará en el hilo de UI.
     * Salidas: Ninguna.
     * Descripción: Ejecuta de forma segura un Runnable en el hilo principal evitando correr
     *              cuando la Activity ya no es válida (finishing/destroyed).
     */
    private void safeRunOnUi(Runnable r) {
        // [Sección] Cortocircuito si la Activity está finalizando o destruida
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;

        // [Sección] Cambio de hilo a UI con verificación redundante
        runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            r.run();
        });
    }

    // --------------------------------------------------------------------------------------------
    // Callback NFC: onTagDiscovered
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas: tag (Tag) - objeto del sistema que representa el tag NFC detectado.
     * Salidas: Ninguna (actualiza UI y ejecuta flujos de lectura/escritura).
     * Descripción: Punto de entrada cuando el dispositivo detecta un tag. Según el modo:
     *   - READ_ARMED: lee campo numérico del sector 1 y lo muestra.
     *   - EDIT_ON_DETECT: detecta el campo numérico, abre diálogo para editar y escribe reemplazo.
     *   - IDLE: muestra recordatorio de seleccionar una acción.
     */
    @Override
    public void onTagDiscovered(Tag tag) {
        long now = System.currentTimeMillis();

        // [Sección] Anti-rebote: ignorar callbacks muy seguidos tras una escritura
        if (now - lastWriteMs < SQUELCH_MS) return;

        switch (mode) {

            // ------------------------------------------------------------------------------------
            // Modo de lectura
            // ------------------------------------------------------------------------------------
            case READ_ARMED: {
                Executors.newSingleThreadExecutor().execute(() -> {
                    // [Sección] Solo soportamos lectura MifareClassic (si no, mostramos aviso)
                    if (MifareClassic.get(tag) != null) {

                        // [Sección] Leer SOLO el número (primer grupo de dígitos >= 3) del sector
                        String numero = MifareClassicHelper.readConcatNumericFromSector(tag, /*sector*/1, MifareClassicHelper.KEY_DEFAULT,/*min*/ 12);

                        // [Sección] Reflejar resultado en la UI
                        safeRunOnUi(() -> {
                            if (numero == null || numero.isEmpty()) {
                                tvSalida.setText("");
                                tvEstado.setText("No se encontró número en el sector 1.");
                                lastReadValue = null;
                            } else {
                                lastReadValue = numero;
                                tvSalida.setText(numero);  // Se muestra solo el número
                                tvEstado.setText("Lectura OK.");
                            }
                            mode = Mode.IDLE;
                        });
                        return;
                    }

                    // [Sección] Tag no compatible con MifareClassic
                    safeRunOnUi(() -> {
                        tvSalida.setText("");
                        tvEstado.setText("Tag no MifareClassic.");
                        mode = Mode.IDLE;
                    });
                });
                break;
            }

            // ------------------------------------------------------------------------------------
            // Modo de edición/escritura
            // ------------------------------------------------------------------------------------
            case EDIT_ON_DETECT: {
                // [Sección] Guardar tag detectado y mostrar techs informativas
                pendingEditTag = tag;
                String techInfo = MifareClassicHelper.techsString(tag);
                safeRunOnUi(() -> tvSalida.setText(techInfo));

                // [Sección] Verificar compatibilidad MifareClassic
                if (MifareClassic.get(tag) != null) {
                    // [Sección] Localizar el campo numérico en sector 1 y luego pedir nuevo valor
                    Executors.newSingleThreadExecutor().execute(() -> {
                        FieldDetect fd = MifareClassicHelper.detectFieldInSector(tag, /*sectorIndex*/1, "\\d+", MifareClassicHelper.KEY_DEFAULT);

                        // [Sección] No se pudo autenticar o no existe campo numérico
                        if (fd == null) {
                            safeRunOnUi(() -> {
                                tvEstado.setText("No se encontró campo numérico en sector 1 (o no se pudo autenticar).");
                                mode = Mode.IDLE;
                                pendingEditTag = null;
                            });
                            return;
                        }

                        // [Sección] Abrir diálogo con valor actual, confirmar y escribir
                        safeRunOnUi(() -> {
                            tvEstado.setText("Valor detectado: " + fd.value + ". Edita y confirma para sobrescribir.");
                            showEditDialog(fd.value, newValue -> {
                                Executors.newSingleThreadExecutor().execute(() -> {

                                    // [Sección] Intento de reemplazo del campo y verificación posterior
                                    WriteOutcome out = MifareClassicHelper.replaceFieldInSector(pendingEditTag, fd, newValue, MifareClassicHelper.KEY_DEFAULT);

                                    // [Sección] Resultado a UI + housekeeping
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

            // ------------------------------------------------------------------------------------
            // Sin modo seleccionado
            // ------------------------------------------------------------------------------------
            case IDLE:
            default:
                runOnUiThread(() -> toast("Elige Leer o Escribir primero."));
        }
    }

    // --------------------------------------------------------------------------------------------
    // Diálogo de edición (UI)
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas:
     *   - prefill (String): valor inicial que aparecerá en el campo de texto (puede ser vacío).
     *   - cb (ConfirmCallback): callback a invocar con el nuevo valor confirmado por el usuario.
     * Salidas: Ninguna directa (el resultado se entrega vía callback).
     * Descripción: Muestra un diálogo con un EditText numérico para que el usuario modifique el
     *              valor y lo confirme. Valida que no sea vacío antes de confirmar.
     */
    private void showEditDialog(String prefill, ConfirmCallback cb) {
        // [Sección] Crear campo de entrada configurado para números
        final EditText input = new EditText(this);
        input.setHint("Número (ej. 88887777)");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(32) });
        if (prefill != null) input.setText(prefill);

        // [Sección] Construir y mostrar el AlertDialog con acciones
        new AlertDialog.Builder(this)
                .setTitle("Editar número del tag")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    // [Sección] Validación simple
                    String val = input.getText().toString().trim();
                    if (val.isEmpty()) {
                        toast("Ingresa un número.");
                        return;
                    }
                    // [Sección] Devolver resultado por callback
                    cb.onConfirm(val);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    // [Sección] Limpiar estado y volver a IDLE
                    tvEstado.setText("Operación cancelada.");
                    pendingEditTag = null;
                    mode = Mode.IDLE;
                })
                .show();
    }
}
