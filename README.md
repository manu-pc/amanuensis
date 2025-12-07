# amanuensis
### Deltarune / UT friendly Localisation Assistant

<details>
<summary>readme, if you haven't played deltarune</summary>

## introduction 
**[amanuensis](https://dictionary.cambridge.org/dictionary/english/amanuensis)** is a simple TUI tool intended to make translating and localising around annoying format markers easier. 

let's say we want to work on localising Deltarune. with the ever-useful UT modding tool, we can extract the dialogue into a .json file with lines like these:

  `"obj_ch2_scene27_slash_Step_0_gml_183_0": "\\Ed* ..^1. but^1, if that wasn't the case.../",`

we *could* try selecting and replacing around all the markers, but for a game with 10k+ lines of dialogue per chapter, this becomes annoying and time-consuming.

instead, let's open the .json file with amanuensis and navigate to this line:
![captura](img/screenshot.png)

wow, actually readable!

and instead of worrying about keeping all the markers to not break the syntax, we just translate the actual dialogue, and the program re-applies all the newline and other format markers correctly, without separating words or breaking out of the dialogue box. how kind!

## how to use

- requires a .json file with a format similar to the included one. the filename will be introduced on the first window
- inside the main localisation window:'
  - top row buttons allow switching between json lines
  - purple label shows read line
  - cyan label shows dialogue without format markers
  - textbox allows inputting of modified text. inside textbox, [Enter] to save and [up/down pag] to switch lines
- will automatically create a .copy.json file with the modified lines

## how to run

**UNIX/MAC**: clone project, run ``run.sh``
**WINDOWS**: clone project, run ``run.bat``




</details>

<details>
<summary>readme, if you have played deltarune</summary>


## HEY EVERY      !!  IT' S ME!!1!
![spamton](img/spamton.png)


EVERYBODY'S [[Number #1 rated]] [[Vibe Coder]]    MANU [PC Componentes]

HERE WITH [Only the latest trends]] IN [[Absolute Dogshit]]!!!!

ARE YOU TIRED OF [Disgusting] FORMAT MARKERS LIKE [\n] [/%] [^1] [And many more!] RUINING YOUR [Pleasant Evening]!?

TRYING TO MAKE AN [[HonestMod]] TO TRANSLATE [The Game] DESPITE NOT HAVING AN OFFICIAL TRANSLATION TOOL??

WELL,       DON'T [!$%X] YET, KID!! FOR THE [Low, low price of]  YOU TOO CAN

YOU TOO CAN


![captura](img/screenshot.png)


LOOK AT THAT [[Lanterna]]!!! YOU DON'T SEE TERMINAL USER INTERFACES LIKE THAT [On TV]

## HELP

- LOAD YOUR [.json shaped] FILE USING THE MAIN [[Windows]]
- THE PROGRAM WILL HANDLE ALL FORMAT MARKERS FOR YOU [[No money back guaranteed!!]] SO YOU CAN [See] AND [Touch] AND [Feel] THE CONTENT OF THE DIALOGUE!!
- [With 3 simple payments] YOU CAN WRITE YOUR TRANSLATION!! NO NEED TO WORRY OR [Die] ABOUT INSERTING NEWLINES, EOF, OR [Hyperlink Blocked]
- [Press the Enter key to] QUICKLY SAVE THE NEW TRANSLATED TEXT, WITH ALL THE [Disgusting] FORMAT MARKERS CORRECTLY RE-APPLIED!! IT'S SO EASY, A [Game Show Host] COULD DO IT!
- YOUR OUTPUT FILE WILL BE GENERATED WITH YOUR BEAUTIFUL LOCALISATION!!1!


## RUN

ALL THE [Scripts] ARE THERE, ON THE [github.com] REPOSITORY, KID! WHAT MORE DO YOU NEED!?



</details>
