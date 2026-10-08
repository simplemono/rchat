# rchat

One agent, one conversation, one machine: a chat UI for a bash agent, for
people who are not developers.

The agent is [mini-swe-agent-clj](https://github.com/maxweber/mini-swe-agent-clj):
a model, a task, bash, nothing else. The UI is
[rframes](https://github.com/simplemono/rframes): the page is a pure
function of one atom, pushed to the browser as hiccup frames. rchat is the
piece between the two, the one that turns an agent run into a conversation:

- **A submission is a handover, not the end.** When the agent submits, it
  waits for the next message of the user, which becomes the next task. One
  conversation, as long as the work takes.
- **Messages steer.** A message that arrives while the agent works is added
  to the conversation before its next model call.
- **Stop and continue.** A stop ends the running command and the run;
  "Continue", or the next message, picks the run up where it stopped. The
  log is saved after every step, so a restart of the process loses nothing.
- **The model sees images.** A command prints `[[look:<path>]]`, and the
  image goes into the conversation: inside the tool result for the
  Anthropic API, as a user message for OpenAI-compatible APIs (OpenRouter).
- **No API key needed to try it.** A script of steps stands in for the
  model, in tests and in the example.
- **Images go both ways.** The user pastes or attaches screenshots, scaled
  in the browser and sent with the message; the agent's commands show the
  model images with a `[[look:<path>]]` marker.
- **A secret link is the login.** One cookie, no accounts.

The server runs on babashka (a JVM is needed to build the browser bundle,
never to run) or on the JVM. The code came out of
[video-agent](https://github.com/storrito/video-agent), where the agent
cuts a video and the user comments on the cut.

## Install

Two modules, as rframes has. The server module goes into `bb.edn` or
`deps.edn`:

```clojure
io.github.simplemono/rchat$server
{:git/url "https://github.com/simplemono/rchat.git"
 :git/sha "<sha>"
 :deps/root "server"}
```

The client module goes into the `deps.edn` of the shadow-cljs build. It
brings rframes' client runtime, parts-cljs, Replicant and transit-cljs:

```clojure
io.github.simplemono/rchat$client
{:git/url "https://github.com/simplemono/rchat.git"
 :git/sha "<sha>"
 :deps/root "client"}
```

## The server

An app owns one atom and builds a runner around it:

```clojure
(defonce db (atom {}))

(def runner
  (rchat.agent/runner
   {:db db
    :dir "/work/.agent"                          ; the log and the images
    :config-fn #(rchat.agent/config
                 {:model "openrouter/moonshotai/kimi-k2"
                  :cwd "/work"
                  :cost-limit 10.0})}))
```

`rchat.agent/config` builds the mini-swe-agent config of a chat agent: it
never asks before a command, a submission hands over, there is no step
limit but a cost limit, and the prompts are generic ones (an app passes
`:system-template` and `:instance-template` for its own job; mini-swe-agent
fills `{{task}}`, `{{cwd}}`, `{{agent_dir}}`, `{{timeout_minutes}}` and
what `start!` gets as `:vars`). A model name is `provider/id` like in
mini-swe-agent-clj, and the default is `openrouter/openai/gpt-6.1-sol`
(GPT-6.1 Sol: $2 and $10 per million tokens, 1M context, a good
trade-off between cost and intelligence); `:text-based? true` picks the
config for a model without tool calling, `:price` gives the USD per
million tokens for an API that does not report the cost.

The runner keeps the agent under `:agent/log` of the atom, next to
`:agent/status` (`:idle`, `:working`, `:waiting`, `:stopped`),
`:agent/pending` (messages the agent has not read yet) and
`:agent/stopping?`. Four functions move it, and the commands of the chat
call them:

| Function | Command | What it does |
|---|---|---|
| `send!` | `:agent/send {:text ...}` | starts the agent, answers a handover, steers, or continues a stopped run with the message |
| `stop!` | `:agent/stop` | ends the running command and the run |
| `resume!` | `:agent/continue` | continues a stopped run, with the cost limit raised if that is why it stopped |
| `load!` | | at start: the saved log, and a waiting agent waits again |

`rchat.agent/register` is the register entries of these commands plus the
route for the images, and `rchat.view/page` is the page. The rest is
rframes:

```clojure
(defn page-view [_w]
  (rchat.view/page @db {:title "my agent"}))

(def register
  [(rframes.http/resources "/rchat.css" "rchat/chat.css")
   (rframes.http/resources "/js/*" "public/js")
   {:replicant/shim "/" :head head}
   {:replicant/render "/" :render/fn #'page-view}])

(defn get-register []
  (rframes.replicant/expand (concat register
                                    rframes.command/register
                                    (rchat.agent/register runner))))

(defn -main [& _]
  (rchat.agent/load! runner)
  (rframes.sse/watch! db)
  (org.httpkit.server/run-server
   (rchat.auth/wrap (rframes.http/ring-handler get-register)
                    {:token (System/getenv "RCHAT_TOKEN")})
   {:port 8080})
  @(promise))
```

`rchat.view/page` takes options: `:title`, `:handover-label`, `:text-fn`
(the text of an item as hiccup children, for an app that turns positions
or links into something clickable), `:placeholder`, `:max-items`, `:card`
(hiccup above the feed) and `:key-missing?`. An app with its own layout
composes `header`, `feed` and `composer` itself. The runner takes
`:on-wait`, called when the agent hands over, and `:after-step`, called
after every step, which is where an app reloads a preview or reads what
the agent wrote.

`rchat.auth/wrap` is the login: `/?token=<secret>` sets a cookie holding
an HMAC of the token, the cookie is the proof on every later request, and
`rchat.auth/link` makes the link. With a nil token, everything passes.

## The browser

The bundle hands rchat's composer to the rframes runtime:

```clojure
(ns my.client
  (:require [rchat.client :as rchat]
            [rframes.client :as rframes]))

(defn init! []
  (rframes/init! {:register rchat/register}))
```

The composer is the one thing the browser owns: the draft lives in the
client store, so a frame that arrives mid-typing does not reset it. Enter
sends, Shift+Enter breaks the line; on a touch device the button sends. A
screenshot pasted into the text, a file dropped on it or picked with the
attach button is scaled in the browser to at most 1600 px, uploaded to
`/upload` and sent with the next message; the agent sees it right after
the text. In the feed, a step is the agent's note and one collapsed "Tool
call" row; the commands and their output are behind it, for whoever wants
them. The agent's notes and handovers are Markdown, rendered on the server
as hiccup (`rchat.markdown`): raw HTML in them stays text and an image
becomes a link, so the model never puts markup or a URL to load on the
page. The user's text is shown as typed.

## The canvas

The creation of the agent beside the chat, like an artifact pane. By
convention the agent writes `<cwd>/canvas/index.html` (the default prompt
says so, `{{canvas_dir}}` in a template of your own), and `rchat.canvas`
serves that directory and shows the page in an iframe:

```clojure
(def canvas
  (rchat.canvas/canvas {:db db
                        :dir "/work/canvas"}))

;; the runner reloads it after every step and at every handover
(rchat.agent/runner {... :after-step (fn [_ _] (rchat.canvas/refresh! canvas))
                         :on-wait (fn [_] (rchat.canvas/refresh! canvas))})

;; the routes next to the chat's, the pane as the page's card
(concat (rchat.agent/register runner) (rchat.canvas/register canvas))
(rchat.view/page @db {:card (rchat.canvas/view @db canvas {})})
```

`refresh!` compares the newest modification time of the directory with
the one in the db; a change bumps `:canvas/rev`, the frame carries a new
`src` and only the iframe reloads, at the scroll position the user had.
Dot paths are never served. `:prepare` is a function over every HTML file
served, for an app that injects a runtime, as video-agent does for
HyperFrames. A HyperFrames composition with its player is the same idea
with video-agent's `:ui/player` alias instead of the iframe.

## The REPL agent

`rchat.repl` is the second kind of agent on the same runner and the one
the example runs by default, after Bret Victor's Inventing on Principle:
the creation lives in the app's own state, the model shapes it with
Clojure forms evaluated in this process, and the user sees every change
the moment it is made, with controls the model builds for them. Runner
`:step rchat.repl/step`, a text-based model config with the prompts
`rchat/prompts/repl-system.md` and `repl-task.md`, and
`rchat.repl/install!` puts the vocabulary into the `user` namespace:
`(design)` and `(design! assoc :title "Hello")` over the `:design` key of
the app atom, `(defn design-view [design] ...)` for the hiccup the user
sees, `:design/set` as the command behind a slider, a color picker or a
text field, `(set-command! ...)` for commands of the model's own, `(done
"...")` to hand over. Every result stays bound to `$n`. The comments of a
message are the note the user reads; the forms sit behind the tool-call
row. `example/src/example/main.clj` is the whole wiring, and
[example/README.md](example/README.md) says what to try.

**Motion.** With `(design! assoc :duration 8)` the creation becomes a
function of time, `(defn design-view [design t] ...)`, and the pane turns
into a player with a scrubber. [fnmotion](https://github.com/simplemono/fnmotion)
is at hand in the REPL as `fm`, `tl` and `captions`, and the same library
runs in the browser: the pane evaluates the model's definitions
(`rchat.repl/definitions`, every `def` and `defn` from the log) with SCI
and renders every frame locally, so scrubbing and playback never touch
the server. Design edits still go through `:design/set`, and a control
inside a frame works like one anywhere else. The client module therefore
carries SCI and fnmotion.

**Server-side motion, measured.** `RCHAT_MOTION=server` is the other way
to play a motion design: `t` lives in the app atom, a server tick pushes
a page frame per step past rframes' coalescing, scrubber and play are
commands, and the browser carries neither SCI nor fnmotion. Measured on
2026-10-08 on one laptop with the scripted six-second clip
(`RCHAT_MOTION=server bb -m example.bench` for the first two lines):

| | server-side | in the browser |
|---|---|---|
| render + encode per frame (babashka) | 0.30 ms, 2% of a core at 60 fps | |
| bytes per frame on the wire | 47 (transit 2.4 kB, gzip against the previous frame) | 0 |
| frames per second played | 55, at 7 to 13% of a core | 53 (the display's refresh) |
| scrub latency, localhost | 12 to 20 ms (120 to 130 ms with the 100 ms coalescing) | 1 to 3 ms |
| scrub latency, remote | plus one round trip: ~40 ms to a Hetzner cell from here, more on a phone | unchanged |
| audio in sync with the frame | no | yes |
| server cost while someone watches | one tick loop per viewer | none |

The push side is cheap; the round trip and audio are what keep the
browser mode the default.

The hazard is the one of any REPL: a form runs with the full authority of
the process. For a sandbox that holds nothing but the user's own creation.
The bash agent (`rchat.agent` with its default step) stays the choice for
work on files and tools outside the app, with the canvas beside it.

## Images

The agent's environment provides a command that stores an image under
`<dir>/looks/<sha>.jpg` and prints `[[look:<path>]]`, like the `look`
script of video-agent (ffmpeg scales it to 1024 px). After the step,
rchat turns the marker into an image block for the model; the log keeps
the reference, the request gets the bytes. The files are content-addressed,
so the conversation is identical from request to request, which prompt
caching needs.

## The example

`example/` has both agents. `example.main` is the REPL agent with the live
creation beside the chat, `example.bash` the bash agent with the canvas.
Without an API key a script plays the model. See
[example/README.md](example/README.md) for what to type.

```bash
cd example
clojure -M -m shadow.cljs.devtools.cli release browser   # the bundle, needs a JVM
bb -m example.main                                       # http://localhost:8080, the REPL agent
bb -m example.bash                                       # the bash agent with the canvas
OPENROUTER_API_KEY=... bb -m example.main                 # GPT-6.1 Sol, RCHAT_MODEL picks another
RCHAT_TOKEN=s3cret bb -m example.main                    # prints the link
```

## Tests

```bash
bb test                        # on babashka; the agent tests run the loop with a scripted model and real bash
cd server && clojure -M:test   # the same on the JVM
```
