# DashCast DevTools

Application Android compagnon de **DashCast**, dédiée au diagnostic,
au reverse engineering et à la validation de la stack de projection BYD
(DiLink 3 / 4 / 5).

## Pourquoi une app séparée ?

DashCast doit rester légère pour les utilisateurs finaux. Les outils
système (sniffer logcat continu, recon des displays / surfaces / services,
validation de la projection cluster) ont leur logique propre, **ne
dépendent pas de l'état interne de DashCast**, et tirent un vrai bénéfice
à pouvoir tourner indépendamment :

- **Sniffer** : peut continuer à capturer **même si DashCast crashe ou
  est arrêté** — c'est exactement le moment le plus intéressant à
  observer.
- **Recon** : tests read-only de l'environnement BYD (displays, services,
  permissions, compat framework). Aucune dépendance à DashCast.
- **Test Fission** : valide la stack de projection (`auto_container` +
  `cmd display create-virtual-display` + MirrorDaemon) **en isolation**
  de DashCast — permet de distinguer un bug DashCast d'un bug ROM.

## État actuel

| Module | Statut |
|---|---|
| Skeleton Gradle + signing bydPlatform | ✅ |
| AdbClient (slim, sans Beta proxy) | ✅ |
| AppLogger | ✅ |
| **Sniffer** | ✅ transplant complet depuis DashCast |
| Recon | ⏳ placeholder — TODO transplant `Dl5ClusterReconRunner` |
| Fission | ⏳ placeholder — TODO transplant `Dl5VdTestRunner` + `MirrorDaemon` |
| i18n 12 locales | ✅ (FR + EN propres, autres = EN à raffiner) |

## Build

```bash
cd DashCastDevTools
./gradlew assembleDebug
# → app/build/outputs/apk/debug/DashCastDevTools-v0.1.0-alpha-debug.apk
```

Signé avec la même clé `platform.keystore` que DashCast (perms BYD OK).

## Plan transplant (prochaines itérations)

1. **Recon** : copier `Platform.java` (DL3/DL5 detection) +
   `DiLink5TestRunner.java` (data model PASS/FAIL/WARN) +
   `Dl5ClusterReconRunner.java`. Adapter UI : ListView de rows + Run All
   + Export.
2. **Fission** : copier `MirrorDaemon.java` + `Dl5VdTestRunner.java`.
   Adapter UI : battery V01..V07 avec dialogs Yes/No interactifs.
3. Une fois validé en voiture, **retirer** les modules correspondants de
   DashCast (DiagActivity slimming pass).

## Conventions

- Package racine : `com.dashcast.devtools`
- ApplicationId : `com.dashcast.devtools` (pas de collision avec DashCast)
- Signature : `bydPlatform` (même keystore — perms signature BYD)
- Min/target SDK : 28 / 29 (compatible DL3 + DL5)
- Strings : 12 locales obligatoires (FR + EN + 10 placeholders)
