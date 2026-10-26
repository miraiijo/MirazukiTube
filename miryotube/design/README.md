# Design de miryo tube

- `design/storyboard.html` : le storyboard de miryo tube. Un seul fichier, à ouvrir dans un navigateur (aucun outil nécessaire).
- Chaque écran y est montré en Jour et en Nuit, avec une séquence touche par touche (étapes cliquables, lecture automatique), et une charte des touches à la fin.
- C'est la référence de l'interface : toute modification d'interface (prévue ou déjà codée) met à jour `storyboard.html` dans le même changement.
- Chaque écran porte une étiquette :
  - **En place** : l'écran existe dans le code actuel, et le storyboard le montre tel que le code le dessine (libellés, ordre, tailles en dp, couleurs de `ui/Theme.kt`) ;
  - **Prévu** : l'écran n'existe pas encore (ou seulement en ébauche dans le code) ; le storyboard montre ce qui est visé.
- Contenus d'exemple inventés (chaînes, vidéos, comptes « Moi » et « Alex »).
