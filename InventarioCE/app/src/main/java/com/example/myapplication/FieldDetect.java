package com.example.myapplication;

/**
 * Descripción general:
 *  Estructura de datos (objeto plano) que encapsula la información de un campo detectado
 *  dentro de un sector MIFARE Classic. Se utiliza para poder referenciar la posición y longitud
 *  del valor encontrado, facilitando su posterior edición o reemplazo.
 */
class FieldDetect {
    // --------------------------------------------------------------------------------------------
    // Atributos
    // -------------------------------------------------------------------------------------------
    /** Descripción: Valor ASCII del campo detectado (por ejemplo "97175"). */
    final String value;

    /** Descripción: Índice del sector donde se encontró el campo (por ejemplo 1). */
    final int sectorIndex;

    /** Descripción: Posición inicial (offset en bytes) dentro del buffer de datos del sector. */
    final int byteStart;

    /** Descripción: Longitud en bytes del valor detectado. */
    final int byteLength;

    // --------------------------------------------------------------------------------------------
    // Constructor
    // --------------------------------------------------------------------------------------------
    /**
     * Entradas:
     *   - value (String): texto del campo detectado.
     *   - sectorIndex (int): número de sector.
     *   - byteStart (int): posición donde comienza el valor dentro de los datos del sector.
     *   - byteLength (int): cantidad de bytes que ocupa el valor.
     * Salidas:
     *   - (FieldDetect): nueva instancia con los valores asignados.
     * Descripción:
     *   Crea un objeto inmutable que representa la ubicación exacta del campo encontrado
     *   por una búsqueda de expresión regular o similar.
     */
    FieldDetect(String value, int sectorIndex, int byteStart, int byteLength) {
        // [Sección] Asignar atributos finales
        this.value = value;
        this.sectorIndex = sectorIndex;
        this.byteStart = byteStart;
        this.byteLength = byteLength;
    }
}
