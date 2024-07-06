import * as Path from "path";
import {window, workspace, type ExtensionContext, commands, tasks, Task, type TaskExecution, ShellExecution, Uri, type TaskDefinition, languages, IndentAction, type Progress, ProgressLocation, debug, type DebugConfiguration, Range, Position, type TextDocument, type TextDocumentContentProvider, type CancellationToken, type ProviderResult, type ConfigurationChangeEvent, type DebugAdapterDescriptorFactory, type DebugAdapterDescriptor, DebugAdapterExecutable, type DebugSession, type DebugAdapterExecutableOptions} from 'vscode';
import {LanguageClient, type LanguageClientOptions, type ServerOptions, NotificationType} from "vscode-languageclient";
import {loadStyles, decoration} from './textMate.js';
import AdmZip from 'adm-zip';

/** Called when extension is activated */
export async function activate(context: ExtensionContext) {
    console.log('Activating Java');

    // Teach VSCode to open JAR files
    workspace.registerTextDocumentContentProvider('jar', new JarFileSystemProvider());

    debug.registerDebugAdapterDescriptorFactory("java", new DebugAdapterExecutableFactory())

    // TODO Download JRE (if required)
    const javaPath = "";
    
    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        // Register the server for java documents
        documentSelector: [{scheme: 'file', language: 'java'}],
        synchronize: {
            // Synchronize the setting section 'java' to the server
            // NOTE: this currently doesn't do anything
            configurationSection: 'java',
            // Notify the server about file changes
            // Keep in sync with src/main/java/org/javacs/JavaLanguageServer.java
            fileEvents: [
                workspace.createFileSystemWatcher('**/*.java'),
                workspace.createFileSystemWatcher('**/pom.xml'),
                workspace.createFileSystemWatcher('**/BUILD'),
                workspace.createFileSystemWatcher('**/MODULE'),
                workspace.createFileSystemWatcher('**/WORKSPACE'),
                workspace.createFileSystemWatcher('**/*.bazel'),
                workspace.createFileSystemWatcher('**/*.bzl'),
            ]
        },
        outputChannelName: 'Java Language Server',
        revealOutputChannelOn: 4, // never
    };

    const languageServerJarPath = "";
    
    // Start the child java process
    let serverOptions: ServerOptions = {
        command: javaPath,
        args: ["-jar", languageServerJarPath],
        options: {
            cwd: context.extensionPath,
        },
    }

    enableJavadocSymbols();

    // Create the language client and start the client.
    let client = new LanguageClient('java', 'Java Language Server', serverOptions, clientOptions);
    let disposable = client.start();

    // Push the disposable to the context's subscriptions so that the 
    // client can be deactivated on extension deactivation
    context.subscriptions.push(disposable);

    // Register test commands
    commands.registerCommand('java.command.test.run', runTest);
    commands.registerCommand('java.command.test.debug', debugTest);
    commands.registerCommand('java.command.findReferences', runFindReferences);

	// When the language client activates, register a progress-listener
    client.onReady().then(() => createProgressListeners(client));

    // Apply semantic colors using custom notification
    function asRange(r: RangeLike) {
        return new Range(asPosition(r.start), asPosition(r.end));
    }
    function asPosition(p: PositionLike) {
        return new Position(p.line, p.character);
    }
    const statics = window.createTextEditorDecorationType({
        fontStyle: 'italic'
    });
    const colors = new Map<string, SemanticColors>();
    function cacheSemanticColors(event: SemanticColors) {
        colors.set(event.uri, event);
        applySemanticColors();
    }
    function applySemanticColors() {
        for (const editor of window.visibleTextEditors) {
            if (editor.document.languageId != 'java') continue;
            const c = colors.get(editor.document.uri.toString());
            if (c == null) {
                console.warn('No semantic colors for ' + editor.document.uri)
                continue;
            }
            function decorate(scope: string, ranges: RangeLike[]) {
                const d = decoration(scope);
                if (d == null) {
                    console.warn(scope + ' is not defined in the current theme');
                    return;
                }
                editor.setDecorations(d, ranges.map(asRange));
            }
            decorate('variable', c.fields);
            editor.setDecorations(statics, c.statics.map(asRange));
        }
    }
    function forgetSemanticColors(doc: TextDocument) {
        colors.delete(doc.uri.toString());
    }
	// Load active color theme
	async function onChangeConfiguration(event: ConfigurationChangeEvent) {
        let colorizationNeedsReload: boolean = event.affectsConfiguration('workbench.colorTheme')
			|| event.affectsConfiguration('editor.tokenColorCustomizations')
		if (colorizationNeedsReload) {
			await loadStyles()
			applySemanticColors()
		}
	}
    client.onReady().then(() => {
        client.onNotification<SemanticColors, unknown>(new NotificationType('java/colors'), cacheSemanticColors);
        context.subscriptions.push(window.onDidChangeVisibleTextEditors(applySemanticColors));
        context.subscriptions.push(workspace.onDidCloseTextDocument(forgetSemanticColors));
        context.subscriptions.push(workspace.onDidChangeConfiguration(onChangeConfiguration))
    });
    await loadStyles();
    applySemanticColors();
}

// Allows VSCode to open files like jar:file:///path/to/dep.jar!/com/foo/Thing.java
class JarFileSystemProvider implements TextDocumentContentProvider {
    private cache = new Map<string, AdmZip>();
    provideTextDocumentContent(uri: Uri, _token: CancellationToken): ProviderResult<string> {
        const {zip, file} = this.splitZipUri(uri);
        return this.readZip(zip, file);
    }
    private splitZipUri(uri: Uri): {zip: string, file: string} {
        const path = uri.fsPath.substring("file://".length);
        const [zip, file] = path.split('!/');
        return {zip, file};
    }
    private readZip(zip: string, file: string): Promise<string> {
        return new Promise((resolve, reject) => {
            try {
                let cached = this.cache.get(zip);
                if (!cached) {
                    cached = new AdmZip(zip);
                    this.cache.set(zip, cached);
                }
                cached.readAsTextAsync(file, resolve);
            } catch (error) {
                reject(error);
            }
        });
    }
}

class DebugAdapterExecutableFactory implements DebugAdapterDescriptorFactory {
    createDebugAdapterDescriptor(
        _session: DebugSession,
        _executable: DebugAdapterExecutable,
    ): ProviderResult<DebugAdapterDescriptor> {
        const javaPath = "";
        const debugServerJar = "";
        const args = [
            "-jar", debugServerJar,
        ];
        const options: DebugAdapterExecutableOptions = {};

        return new DebugAdapterExecutable(javaPath, args, options);
    }

}

// this method is called when your extension is deactivated
export function deactivate() {
}

function runFindReferences(uri: string, lineNumber: number, column: number) {
    // LSP is 0-based but VSCode is 1-based
    return commands.executeCommand('editor.action.findReferences', Uri.parse(uri), {lineNumber: lineNumber+1, column: column+1});
}

interface JavaTestTask extends TaskDefinition {
    className: string
    methodName: string
}

function runTest(sourceUri: string, className: string, methodName: string|null): Thenable<TaskExecution>|false {
    let file = Uri.parse(sourceUri).fsPath;
    // @ts-expect-error
    file = Path.relative(workspace.rootPath, file);
	let test: JavaTestTask = {
		type: 'java.task.test',
        className: className,
        // @ts-expect-error
        methodName: methodName,
    }
    let shell = testShell(file, className, methodName);
    if (shell == null) return false;
	let workspaceFolder = workspace.getWorkspaceFolder(Uri.parse(sourceUri));
    // @ts-expect-error
	let testTask = new Task(test, workspaceFolder, 'Java Test', 'Java Language Server', shell);
	return tasks.executeTask(testTask)
}

function testShell(file: string, className: string, methodName: string|null) {
    let config = workspace.getConfiguration('java')
    // Run method or class
    if (methodName != null) {
        let command = config.get('testMethod') as string[]
        if (command.length == 0) {
            window.showErrorMessage('Set "java.testMethod" in .vscode/settings.json');
            return null;
        } else {
            return templateCommand(command, file, className, methodName)
        }
    } else {
        let command = config.get('testClass') as string[]
        if (command.length == 0) {
            window.showErrorMessage('Set "java.testClass" in .vscode/settings.json');
            return null;
        } else {
            return templateCommand(command, file, className, "")
        }
    }
}

async function debugTest(sourceUri: string, className: string, methodName: string, sourceRoots: string[]): Promise<boolean> {
    let file = Uri.parse(sourceUri).fsPath;
    // @ts-expect-error
    file = Path.relative(workspace.rootPath, file);
    // Run the test in its own shell
	let test: JavaTestTask = {
		type: 'java.task.test',
        className: className,
        methodName: methodName,
    }
    let shell = debugTestShell(file, className, methodName);
    if (shell == null) return false;
	let workspaceFolder = workspace.getWorkspaceFolder(Uri.parse(sourceUri));
    if (!workspaceFolder) return false;
	let testTask = new Task(test, workspaceFolder, 'Java Test', 'Java Language Server', shell);
    await tasks.executeTask(testTask);
    // Attach to the running test
	let attach: DebugConfiguration = {
        name: 'Java Debug',
        type: 'java',
        request: 'attach',
        port: 5005,
        sourceRoots: sourceRoots,
    }
    console.log('Debug', JSON.stringify(attach));
    return debug.startDebugging(workspaceFolder, attach);
}

function debugTestShell(file: string, className: string, methodName: string) {
    let config = workspace.getConfiguration('java')
    let command = config.get('debugTestMethod') as string[]
    if (command.length == 0) {
        window.showErrorMessage('Set "java.debugTestMethod" in .vscode/settings.json');
        return null;
    } else {
        return templateCommand(command, file, className, methodName)
    }
}

function templateCommand(command: string[], file: string, className: string, methodName: string) {
    // Replace template parameters
    var replaced = []
    for (var i = 0; i < command.length; i++) {
        let c = command[i]
        c = c.replace('${file}', file)
        c = c.replace('${class}', className)
        c = c.replace('${method}', methodName)
        replaced[i] = c
    }
    // Populate env
    let env = {...process.env} as {[key: string]: string};
    return new ShellExecution(replaced[0], replaced.slice(1), {env})
}

interface ProgressMessage {
    message: string 
    increment: number
}

function createProgressListeners(client: LanguageClient) {
	// Create a "checking files" progress indicator
	let progressListener = new class {
		progress?: Progress<{message: string, increment?: number}>
		resolve?: (nothing: {}) => void
		
		startProgress(message: string) {
            if (this.progress != null)
                this.endProgress();

            window.withProgress({title: message, location: ProgressLocation.Notification}, progress => new Promise((resolve, _reject) => {
                this.progress = progress;
                this.resolve = resolve;
            }));
		}
		
		reportProgress(message: string, increment: number) {
            if (increment == -1)
                this.progress?.report({message});
            else 
                this.progress?.report({message, increment})
		}

		endProgress() {
            if (this.progress != null) {
                this.resolve?.({});
                this.progress = undefined;
                this.resolve = undefined;
            }
		}
	}
	// Use custom notifications to drive progressListener
	client.onNotification<ProgressMessage, unknown>(new NotificationType('java/startProgress'), (event) => {
		progressListener.startProgress(event.message);
	});
	client.onNotification<ProgressMessage, unknown>(new NotificationType('java/reportProgress'), (event) => {
		progressListener.reportProgress(event.message, event.increment);
	});
	client.onNotification(new NotificationType('java/endProgress'), () => {
		progressListener.endProgress();
	});
};

interface SemanticColors {
    uri: string;
    fields: RangeLike[];
    statics: RangeLike[];
}

interface RangeLike {
    start: PositionLike;
    end: PositionLike;
}

interface PositionLike {
    line: number;
    character: number;
}

function enableJavadocSymbols() {
	// Let's enable Javadoc symbols autocompletion, shamelessly copied from MIT licensed code at
	// https://github.com/Microsoft/vscode/blob/9d611d4dfd5a4a101b5201b8c9e21af97f06e7a7/extensions/typescript/src/typescriptMain.ts#L186
	languages.setLanguageConfiguration('java', {
		indentationRules: {
			// ^(.*\*/)?\s*\}.*$
			decreaseIndentPattern: /^(.*\*\/)?\s*\}.*$/,
			// ^.*\{[^}"']*$
			increaseIndentPattern: /^.*\{[^}"']*$/
		},
		wordPattern: /(-?\d*\.\d\w*)|([^\`\~\!\@\#\%\^\&\*\(\)\-\=\+\[\{\]\}\\\|\;\:\'\"\,\.\<\>\/\?\s]+)/g,
		onEnterRules: [
			{
				// e.g. /** | */
				beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
				afterText: /^\s*\*\/$/,
				action: { indentAction: IndentAction.IndentOutdent, appendText: ' * ' }
			},
			{
				// e.g. /** ...|
				beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
				action: { indentAction: IndentAction.None, appendText: ' * ' }
			},
			{
				// e.g.  * ...|
				beforeText: /^(\t|(\ \ ))*\ \*(\ ([^\*]|\*(?!\/))*)?$/,
				action: { indentAction: IndentAction.None, appendText: '* ' }
			},
			{
				// e.g.  */|
				beforeText: /^(\t|(\ \ ))*\ \*\/\s*$/,
				action: { indentAction: IndentAction.None, removeText: 1 }
			},
			{
				// e.g.  *-----*/|
				beforeText: /^(\t|(\ \ ))*\ \*[^/]*\*\/\s*$/,
				action: { indentAction: IndentAction.None, removeText: 1 }
			}
		]
	});
}
