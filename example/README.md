# The example

Two agents, one chat page. Build the browser bundle once (needs a JVM),
then start either:

```bash
clojure -M -m shadow.cljs.devtools.cli release browser
bb -m example.main     # the REPL agent: the creation lives in the app, controls to play with it
bb -m example.bash     # the bash agent: works in ./work, its page in ./work/canvas beside the chat
```

Open http://localhost:8080. Without an API key a script plays the model
(`resources/repl-script.edn`, `resources/script.edn`): type anything, press
Enter, and watch.

With the real model, GPT-6.1 Sol through OpenRouter:

```bash
export OPENROUTER_API_KEY=...          # or put it into a .env and `set -a; . .env; set +a`
RCHAT_WORK=/tmp/rchat-try bb -m example.main
```

| Variable | Meaning |
|---|---|
| `OPENROUTER_API_KEY` | the key; `ANTHROPIC_API_KEY` alone runs Claude directly |
| `RCHAT_MODEL` | `provider/id`, default `openrouter/openai/gpt-6.1-sol` |
| `RCHAT_WORK` | the agent's directory, default `./work`; the log in `.agent/` resumes the conversation on the next start |
| `RCHAT_TOKEN` | a secret: the server prints the link that sets the cookie, nobody else gets in |
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

## What to type to the bash agent

> Build an HTML page with a review card for a small hotel: headline, five
> stars, a quote, the guest's name. Put it on my canvas.

Then "make the headline bigger", "use a cooler palette", "add a photo
placeholder". The page in `./work/canvas/index.html` reloads beside the
chat whenever the agent changes it.

## Stopping

Ctrl-C stops the server; the next start resumes the conversation from the
log. A stuck one: `fuser -k 8080/tcp`.
