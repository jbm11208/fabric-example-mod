# Client-Side Ai Bot for Minecraft
Credit to:

TerminalCalamity for providing the AutoSocial code that this mod is based on

Cameron7108 for the python code that inspired this mod

## Setup

1. Be sure you have Fabric installed on Minecraft 26.2

2. (IF USING OLLAMA) Download Ollama from https://ollama.com and pull the ai model you wish to use (if choosing a cloud model, be sure to run it before using in the mod so you can sign in to Ollama from the terminal)

3. (IF USING OPENAI) Put your OpenAI API key into the dedicated OpenAI API key slot in the config menu and input the name of the model you desire to use (you will need to wait until a later step to do this)

4. Download a copy of yt-dlp if you would like the model to be able to play sounds from YouTube (the exe executable, available at https://github.com/yt-dlp/yt-dlp/releases )

5. Make sure ffmpeg is installed and added to your path if you want the model to be able to play any sounds at all

6. If you would like to have the mod play sound through a VC mod, make sure you have VB-Cable installed, and set your mic to be the VB-Cable output.

7. Compile the mod and put both output jar files in the mods folder of your Minecraft instance

8. Run the Minecraft instance, and be sure to bind the skip, config menu, toggle video hud, and reload config buttons in the keybinds setting in options

9. Press the config menu key you just binded (go to step 3 if using OpenAI) (you can also access the config by navigating to .minecraft\config\autosocial\autosocial.yml)

10. Now, modify the config file to point to your yt-dlp executable and change the system prompt to your desire (be sure to keep the notes that mention audio if you would like to be able to use it, and keep everything after "Keep your responses to 3 sentences or less")

11. If you would like to use ElevenLabs TTS, insert your API key and the Voice ID of the voice you would like to use in the config menu now.

12. If you would like to use "Current TTS" (free TTS), you just have to set the TTS to that and no other action is required.

13. If you would like to not use TTS at all, the program will look for WAV files in the "C:\Users\{your account name}\Documents\autosocial\Wario\" if you would like to have it play random sounds every 3 words instead. (this is not the "Documents" folder that shows up in File Explorer by default, you will have to navigate to it manually by going to C:\ and navigating through each folder manually)

14. If you are not on the DougDoug Minecraft Server, uncheck "Add 'IAMAB0T' to messages"

15. Hit "save" on the config menu once you are done changing the configuration

16. Have fun with your new ai bot!

<img width="664" height="112" alt="image" src="https://github.com/user-attachments/assets/081c19b9-0f07-4e64-b05c-e420200ab34e" />

### WAAHHHHH!!!!

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
