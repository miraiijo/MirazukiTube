# miryo tube

YouTube pour Android TV avec l'interface de miryo : sans publicité, avec SponsorBlock, DeArrow,
plusieurs comptes, un profil Invité et un profil Enfant.

## Un fork de SmartTube

miryo tube est un **fork de [SmartTube](https://github.com/yuliskov/SmartTube)**, de yuliskov.
Son interface est nouvelle (Compose for TV, dans [`miryotube/`](miryotube/)). La lecture et
l'accès à YouTube viennent du moteur de SmartTube, utilisé tel quel :

- `MediaServiceCore` et `SharedModules` (sous-modules du dépôt de SmartTube) ;
- l'ExoPlayer modifié de SmartTube, qui sait lire SABR.

À part ce README, les fichiers de SmartTube ne sont pas modifiés : tout le travail de miryo tube est dans `miryotube/`
et `.github/workflows/miryotube.yml`. Le README d'origine de SmartTube est conservé dans
[README-SmartTube.md](README-SmartTube.md).

miryo tube est une appli **non officielle**. Elle n'a aucun lien avec SmartTube, YouTube ou Google.
Merci de ne pas signaler les problèmes de miryo tube à SmartTube.

## Licences

- Le code de SmartTube est sous [licence MIT](LICENSE), Copyright (c) 2020-present yuliskov.
  Cet avis est conservé dans le dépôt, il est affiché dans l'appli (Réglages › À propos) et il
  est fourni dans l'APK (`assets/licenses`).
- Composants tiers : ExoPlayer, AndroidX, Cronet, OkHttp, RxJava et ZXing sont sous licence
  Apache 2.0. Glide est sous licences BSD, MIT et Apache 2.0. La police Inter est sous
  SIL Open Font License 1.1.

## Construire

Voir [miryotube/README.md](miryotube/README.md) : build, CI, mises à jour, lien avec miryo et
synchronisation avec SmartTube.
