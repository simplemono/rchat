# The example

Two agents, one chat page. `bb start` compiles the browser bundle when it
is missing or older than the sources (that step needs a JVM and the
clojure CLI), reads a `.env` beside it, and starts the REPL agent:

```bash
bb start    # the REPL agent: the creation lives in the app, controls to play with it
bb bash     # the bash agent: works in ./work, its page in ./work/canvas beside the chat
bb build    # only the bundle
bb bench    # what a server-pushed frame costs
```

Open http://localhost:8080. Without an API key a script plays the model
(`resources/repl-script.edn`, `resources/script.edn`): type anything, press
Enter, and watch.

With the real model, GPT-6.1 Sol through OpenRouter, put the key into
`example/.env` (ignored by git) or export it:

```bash
echo 'OPENROUTER_API_KEY=...' > .env
RCHAT_WORK=/tmp/rchat-try bb start
```

| Variable | Meaning |
|---|---|
| `OPENROUTER_API_KEY` | the key; `ANTHROPIC_API_KEY` alone runs Claude directly |
| `RCHAT_MODEL` | `provider/id`, default `openrouter/openai/gpt-6.1-sol` |
| `RCHAT_WORK` | the agent's directory, default `./work`; the log in `.agent/` resumes the conversation on the next start |
| `RCHAT_TOKEN` | a secret: the server prints the link that sets the cookie, nobody else gets in |
| `RCHAT_MOTION` | `server` (default) renders the motion pane on the server, `browser` plays it in the browser with SCI |
| `PORT` | default 8080 |

## What to type to the REPL agent

Start with one sentence that names the thing and asks for controls, then
push it in rounds. A round with the real model costs about a cent.

First message:

> Build a guest review card for a small hotel, like the ones posted on
> Instagram: hotel name, a headline, five stars, a quote and the guest's
> name. Give me controls to play with it.

Rounds that show what the harness can do:

- "Make the quote size a number I can drag." — a control for a value that had none
- "Add a button that shuffles the color palette." — a command the model adds at runtime
- "Show two variants side by side, warm and cool, with one set of controls for both." — the view is a pure function of the data
- "Add a dropdown for the font: serif, sans, handwritten."
- "Make it square, 1080 by 1080, so I can post it."
- Paste a screenshot of a design you like into the message: "Make it look like this."
- "Undo the last change."

Things to watch, this is the experiment:

- Does the view still work after three rounds, or does the model start fighting its own code?
- Does a control it added survive the next redefinition of the view?
- Does a round cost more than a few cents? The header shows steps and dollars.

Behind "Tool call" is what the model wrote and what the REPL answered;
"The code of your creation" under the pane is the latest `design-view`.
If something breaks, paste the error back into the chat as it is: the
model sees the same REPL output.

## What to type for motion

Time is one more dimension of the same agent. As soon as the design has
a `:duration` and the view takes a time, the pane beside the chat
becomes a player with a scrubber. Under the hood it is
[fnmotion](https://github.com/simplemono/fnmotion): a frame is a pure
function of the design and the time in seconds, nothing is pre-rendered.
Start here with an empty conversation, nothing above is needed. Without
an API key the script answers the first two messages with a still card
and the third with a clip, whatever you type.

First message:

> Make a six-second clip for an Instagram story about a small hotel: the
> hotel name springs in, then "Guests love it here. Come and see."
> appears word by word as if spoken, with the spoken word in an accent
> color, and a thin bar at the bottom shows how far we are. Give me
> controls for the colors and the text size.

If you built the review card above first, "Turn the card into a
six-second clip, …" with the same sentence does the same from there.

Rounds that show what the player can do:

- "Make it ten seconds and let the title stay longer." — the length lives in the design, the scrubber follows at once
- "Slower entrance, more bounce." — the spring and the easings of `fm`
- "Make the bounce a slider." — a control for a motion value; drag it while the clip plays
- "After the quote, a closing card with the hotel name and 'Book now', held for two seconds." — scenes one after the other with `tl/sequence`
- "Here are the real word timings: …" paste a transcript with `{:start :end :text}` per word, or dictate it: "the words start at 0.5 s, about 0.4 s each, a pause before 'Come'"
- "At most two words per line." — the caption blocks, `captions/captions` with `:max-chars`
- "Make the highlighted word jump a little."
- "Play it twice." — `tl/repeat`
- "Story size, 1080 by 1920."
- "Show me the frame at 2.5 seconds." — the model looks with `(frame-at 2.5)` at the REPL and answers from it

Things to watch:

- Drag the scrubber back and forth: every position is the same function, called with another `t`. There is no cache to miss and no frame that looks different from its neighbours.
- Change a color or the size while it plays. A control in a frame works like a control in a still card.
- Does the model keep `design-view` a function of the design and `t` only? If it reaches for `db` or `sh` inside the frame, say "keep it a pure function of the design and the time": the same function has to render the frames in the final video.
- Does a new duration or a new scene order break the frames that worked before? Scrub through the whole clip after every round.

By default the server renders every frame and pushes the diff
(`RCHAT_MOTION=server`); `RCHAT_MOTION=browser` evaluates the same
definitions in the browser instead. What you type is the same in both.

## What to type to the bash agent

> Build an HTML page with a review card for a small hotel: headline, five
> stars, a quote, the guest's name. Put it on my canvas.

Then "make the headline bigger", "use a cooler palette", "add a photo
placeholder". The page in `./work/canvas/index.html` reloads beside the
chat whenever the agent changes it.

## Stopping

Ctrl-C stops the server; the next `bb start` resumes the conversation from
the log and rebuilds the creation from it: the definitions and commands
the model evaluated, and the design as it was at the last message,
including what you changed with the controls. It prints how many
definitions it rebuilt and which failed, if any. A stuck one:
`fuser -k 8080/tcp`.
