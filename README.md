# MDVAspectos

Plugin custom para MDVCRAFT. Abre un menu con `/aspecto` y muestra solamente el catalogo de aspectos correspondiente a la raza/clase actual del jugador en MMOCore.

## Requisitos

- Java 21
- Purpur/Paper 1.21.6
- PlaceholderAPI
- MMOCore con placeholders funcionando
- SkinsRestorer

## Funcionamiento

- `/aspecto` abre el menu de aspectos.
- El plugin lee la raza con `%mmocore_class_id%`.
- Si el jugador esta en una clase default/sin raza, el menu no se abre.
- Si el jugador es `elfo`, solo ve el catalogo `elfo`.
- Si el jugador es `orco`, solo ve el catalogo `orco`.
- Al hacer click en una cabeza, se ejecuta desde consola:

```txt
skin set {skin} {player}
```

Ejemplo:

```txt
skin set elfo_0 Julian
```

## Configuracion rapida

Edita `plugins/MDVAspectos/config.yml`.

Lo mas importante es ajustar los aliases de cada catalogo al ID real de tus clases de MMOCore:

```yaml
catalogs:
  elfo:
    aliases:
      - 'elfo'
      - 'ELFO'
```

Si `%mmocore_class_id%` devuelve otro nombre, agregalo como alias.

## Cabezas con textura

El plugin intenta usar la API de SkinsRestorer para obtener la textura de cada skin guardada. Si una cabeza no carga textura, puedes poner la textura manual:

```yaml
skin_ejemplo:
  slot: 20
  skin: 'elfo_0'
  name: '&aElfo I'
  texture-value: 'BASE64_AQUI'
  texture-signature: 'FIRMA_AQUI'
```

## Compilar con GitHub Actions

Sube el proyecto a GitHub, entra a `Actions`, ejecuta `Build MDVAspectos` y descarga el artifact `MDVAspectos`.

## Comandos

```txt
/aspecto
/aspectos
/aspecto reload
```

## Permisos

```txt
mdvaspectos.use      default: true
mdvaspectos.reload   default: op
```


## MDVAspectos 1.0.2

Novedades:

- Agrega `buttons:` globales para mostrar botones extra en todos los catalogos.
- Agrega `catalogs.<catalogo>.buttons:` para botones especificos por raza/catalogo.
- Los botones pueden ejecutar comandos como jugador o desde consola.
- Soporta cabezas custom con `texture`, `custom-head-texture`, `head-texture`, `skull-texture`, `skull_texture` o `texture-base64`.

Ejemplo global:

```yaml
buttons:
  social-main:
    enabled: true
    slot: 49
    material: PLAYER_HEAD
    texture: 'BASE64_DE_CASITA'
    name: '&a&lMenu principal'
    lore:
      - '&7Vuelve al menu principal de MDVSocial.'
    close-on-click: true
    commands:
      - 'social main'
```

Ejemplo por catalogo:

```yaml
catalogs:
  humano:
    buttons:
      perfil-social:
        slot: 48
        material: PLAYER_HEAD
        texture: 'BASE64_DE_VOLVER'
        name: '&6&lVolver al perfil'
        commands:
          - 'social perfil'
```

## MDVAspectos 1.1.1 - memoria segura de skins libres

Además de recordar skins del catálogo, esta versión puede recordar comandos de jugador como `/skin conan`, pero solo si está activado y el jugador tiene permiso.

Configuración recomendada para permitir skins fuera del catálogo:

```yaml
skin-memory:
  listen-player-skin-commands: true
  only-configured-skins: false
  player-command-require-permission: true
  player-command-required-permissions:
    - 'mdvaspectos.skinmemory.free'
```

Da `mdvaspectos.skinmemory.free` solo a rangos que realmente puedan usar skins libres. Los jugadores sin permiso pueden escribir `/skin conan`, pero MDVAspectos no lo guardará.


## MDVAspectos 1.1.2 - Race Command Gate

Incluye un bloqueo global de comandos para obligar a los jugadores a elegir raza antes de usar el servidor.

Permisos recomendados:

```mcfunction
/lp group aventurero permission set mdvcraft.race.selected true
/lp group admin permission set mdvcraft.racegate.bypass true
```

El bloqueo se configura en `race-command-gate:` dentro de `config.yml`.
