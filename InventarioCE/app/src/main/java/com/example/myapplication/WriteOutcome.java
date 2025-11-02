package com.example.myapplication;

/**
 * Descripción general:
 *  Clase que encapsula el resultado de una operación de escritura (o lectura) MIFARE.
 *  Incluye tanto un mensaje amigable para el usuario como un detalle técnico interno.
 *  Se utiliza para estandarizar las respuestas de éxito o fallo en las operaciones NFC.
 */
class WriteOutcome {
    // --------------------------------------------------------------------------------------------
    // Atributos
    // -------------------------------------------------------------------------------------------
    /** Descripción: Indica si la operación fue exitosa (true) o falló (false). */
    final boolean ok;

    /** Descripción: Mensaje legible para mostrar en UI al usuario. */
    final String userMessage;

    /** Descripción: Detalle técnico (útil para logs o depuración). */
    final String technicalDetail;

    // --------------------------------------------------------------------------------------------
    // Constructor
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas:
     *   - ok (boolean): indica éxito (true) o fallo (false).
     *   - userMessage (String): texto legible para el usuario.
     *   - technicalDetail (String): detalle técnico para depuración.
     * Salidas:
     *   - (WriteOutcome): nueva instancia con los datos del resultado.
     * Descripción:
     *   Construye un objeto resultado que permite comunicar tanto al usuario como al
     *   desarrollador el resultado de la operación NFC.
     */
    WriteOutcome(boolean ok, String userMessage, String technicalDetail) {
        // [Sección] Asignar atributos inmutables
        this.ok = ok;
        this.userMessage = userMessage;
        this.technicalDetail = technicalDetail;
    }

    // --------------------------------------------------------------------------------------------
    // Métodos de fábrica (helpers estáticos)
    // --------------------------------------------------------------------------------------------

    /**
     * Entradas:
     *   - msg (String): mensaje de éxito para el usuario.
     * Salidas:
     *   - (WriteOutcome): resultado marcado como exitoso.
     * Descripción:
     *   Crea rápidamente una instancia de éxito (ok = true) con un mensaje
     *   y sin detalle técnico adicional.
     */
    static WriteOutcome ok(String msg) {
        return new WriteOutcome(true, msg, "");
    }

    /**
     * Entradas:
     *   - userMsg (String): mensaje visible para el usuario.
     *   - tech (String): detalle técnico interno.
     * Salidas:
     *   - (WriteOutcome): resultado marcado como fallo (ok = false).
     * Descripción:
     *   Crea una instancia de error que incluye tanto un mensaje visible
     *   como el detalle para depuración o registro en logs.
     */
    static WriteOutcome fail(String userMsg, String tech) {
        return new WriteOutcome(false, userMsg, tech);
    }
}
