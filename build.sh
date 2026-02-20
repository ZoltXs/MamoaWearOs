#!/bin/bash

echo "======================================"
echo "  Mamoa Notificaciones v2.0"
echo "  Script de Compilación"
echo "======================================"
echo ""

# Colores para output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Verificar si existe Android SDK
if [ -z "$ANDROID_HOME" ]; then
    echo -e "${RED}ERROR: ANDROID_HOME no está configurado${NC}"
    echo "Por favor, instala Android Studio o configura ANDROID_HOME"
    exit 1
fi

echo -e "${BLUE}1. Limpiando proyecto...${NC}"
./gradlew clean

echo ""
echo -e "${BLUE}2. Compilando APK debug...${NC}"
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓ Compilación exitosa!${NC}"
    echo ""
    echo "APK generado en:"
    echo "  app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    
    # Copiar APK a directorio de salida
    mkdir -p output
    cp app/build/outputs/apk/debug/app-debug.apk output/mamoa-v2.0-debug.apk
    
    echo -e "${GREEN}APK copiado a: output/mamoa-v2.0-debug.apk${NC}"
    echo ""
    echo "Para firmar el APK, ejecuta:"
    echo "  ./sign.sh"
    echo ""
else
    echo -e "${RED}✗ Error en la compilación${NC}"
    exit 1
fi
