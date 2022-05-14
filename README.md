### Quiltflower

Quiltflower is a modern, general purpose decompiler focused on improving code quality, speed, and usability. Quiltflower is a fork of Fernflower and Forgeflower.

Changes include:
- New language features (Try with resources, switch expressions, pattern matching, and more)
- Better control flow generation (loops, try-catch, and switch, etc.)
- More configurability
- Better error messages
- Javadoc application
- Multithreading
- Optimization
- Many other miscellaneous features and fixes

### Use
Want to use Quiltflower? There are a few ways! For Fabric and Architectury projects, [Loom Quiltflower](https://github.com/Juuxel/LoomQuiltflower) allows you to run genSources with Quiltflower.
The [Quiltflower Intellij IDEA plugin](https://plugins.jetbrains.com/plugin/18032-quiltflower) replaces Fernflower in IDEA with Quiltflower, and allows you to modify its settings.
Or, if you want to run Quiltflower from the commandline, head over to the [Releases tab](https://github.com/QuiltMC/quiltflower/releases) and grab the latest, and then follow the instructions further down the readme.
Make sure to report any issues to the [Issues tab!](https://github.com/QuiltMC/quiltflower/issues)

For support or questions, please join the [Quilt toolchain discord.](https://discord.quiltmc.org/toolchain)

### Contributing
To contribute, please check out [CONTRIBUTING.md](./CONTRIBUTING.md) and [ARCHITECTURE.md](./ARCHITECTURE.md)!

When pulling from upstream, use https://github.com/fesh0r/fernflower

#### Special Thanks
* Jetbrains- For maintaining Fernflower
* Forge Team- For maintaining ForgeFlower
* CFR- For it's large suite of very useful tests

Fernflower's readme is preserved below:
### About Fernflower

Fernflower is the first actually working analytical decompiler for Java and 
probably for a high-level programming language in general. Naturally it is still 
under development, please send your bug reports and improvement suggestions to the
[issue tracker](https://github.com/QuiltMC/quiltflower/issues).

### Licence

Fernflower is licenced under the [Apache Licence Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

### Running from command line

`java -jar quiltflower.jar [-<option>=<value>]* [<source>]+ <destination>`

\* means 0 or more times\
\+ means 1 or more times

\<source>: file or directory with files to be decompiled. Directories are recursively scanned. Allowed file extensions are class, zip and jar.
          Sources prefixed with -e= mean "library" files that won't be decompiled, but taken into account when analysing relationships between 
          classes or methods. Especially renaming of identifiers (s. option 'ren') can benefit from information about external classes.          

\<destination>: destination directory 

\<option>, \<value>: a command-line option with the corresponding value (see "Command-line options" below).

##### Examples:

`java -jar quiltflower.jar -hes=0 -hdc=0 c:\Temp\binary\ -e=c:\Java\rt.jar c:\Temp\source\`

`java -jar quiltflower.jar -dgs=1 c:\Temp\binary\library.jar c:\Temp\binary\Boot.class c:\Temp\source\`

### Command-line options
To force saving as a file or folder, `--file` and `--folder` can be provided. If not specified, Quiltflower will try to guess based on the file name.

With the exception of mpm, urc, ind, thr and log, the value of 1 means the option is activated, 0 - deactivated. Default 
value, if any, is given between parentheses.

Typically, the following options will be changed by user, if any: hes, hdc, dgs, mpm, ren, urc, ind, thr, tlf, tco
The rest of options can be left as they are: they are aimed at professional reverse engineers.

- rbr (1): Hide bridge methods
- rsy (1): Hide synthetic class members
- din (1): Decompile inner classes
- dc4 (1): Collapse 1.4 class references
- das (1): Decompile assertions
- hes (1): Hide empty super invocation
- hdc (1): Hide empty default constructor
- dgs (1): Decompile generic signatures
- ner (1): Assume return not throwing exceptions
- esm (1): Ensure synchronized ranges are complete
- den (1): Decompile enumerations
- rgn (1): Remove getClass() invocation, when it is part of a qualified new statement
- lit (0): Output numeric literals "as-is"
- bto (1): Interpret int 1 as boolean true (workaround to a compiler bug)
- asc (0): Encode non-ASCII characters in string and character literals as Unicode escapes
- nns (0): Allow for not set synthetic attribute (workaround to a compiler bug)
- uto (1): Consider nameless types as java.lang.Object (workaround to a compiler architecture flaw)
- udv (1): Reconstruct variable names from debug information, if present
- ump (1): Use method parameter names from the MethodParameter attribute.
- rer (1): Remove empty exception ranges
- fdi (1): De-inline finally structures
- inn (1): Check for IntelliJ IDEA-specific `@NotNull` annotation and remove inserted code if found
- lac (0): Decompile lambda expressions to anonymous classes
- bsm (0): Add mappings for source bytecode instructions to decompiled code lines
- dcl (0): Dump line mappings to output archive zip entry extra data
- iib (0): Ignore invalid bytecode
- vac (0): Verify that anonymous classes can be anonymous
- tcs (0): Simplify boolean constants in ternary operations
- pam (0): Decompile pattern matching
- tlf (0): Experimental try loop enhancements (may cause some methods to decompile wrong or not at all!)
- tco (1): Allow ternaries to be generated in if and loop conditions
- swe (1): Decompile Switch Expressions in modern Java
- shs (0): Display code blocks hidden, for debugging purposes
- ovr (1): Show override annotations for methods known to the decompiler.
- ssp (1): Second-Pass Stack Simplficiation
- iec (0): Give the decompiler information about every jar on the classpath.
- jrt (0/if running from CLI, `current`): The path to a java runtime to add to the classpath, or `1` or `current` to add the java runtime of the active JVM to the classpath.
- ega (0): Explicit Generic Arguments
- isl (1): Inline simple lambdas
- log (INFO): A logging level, possible values are TRACE, INFO, WARN, ERROR
- mpm (0): [DEPRECATED] max processing time per decompiled method, in seconds. 0 means no upper limit
- ren (0): Rename ambiguous (resp. obfuscated) classes and class elements
- urc (-): Full name of a user-supplied class implementing IIdentifierRenamer interface. It is used to determine which class identifiers
           should be renamed and provides new identifier names (see "Renaming identifiers")
- nls (0): define new line character to be used for output. 0 - '\r\n' (Windows), 1 - '\n' (Unix), default is OS-dependent
- ind (3 spaces): Indentation string
- pll (160): Max line length before formatting
- ban (-): Banner to display before every root class definition
- erm (-): Message to display when a decomplication error occurs
- thr: maximum number of threads (default is number of threads available to the JVM)
- jvn (0): Use jad variable naming for local variables
- sef (0): Skip copying non-class files from the input folder or file to the output
- win (1): Warn about inconsistent inner class attributes
- dbe (1): Dump bytecode on errors
- dee (1): Dump exceptions on errors
- dec (1): Decompiler error comments
- sfc (0): Debug comments showing the class SourceFile attribute if present

### Renaming identifiers

Some obfuscators give classes and their member elements short, meaningless and above all ambiguous names. Recompiling of such
code leads to a great number of conflicts. Therefore it is advisable to let the decompiler rename elements in its turn, 
ensuring uniqueness of each identifier.

Option 'ren' (i.e. -ren=1) activates renaming functionality. Default renaming strategy goes as follows:
- rename an element if its name is a reserved word or is shorter than 3 characters
- new names are built according to a simple pattern: (class|method|field)_\<consecutive unique number>  
You can overwrite this rules by providing your own implementation of the 4 key methods invoked by the decompiler while renaming. Simply 
pass a class that implements org.jetbrains.java.decompiler.main.extern.IIdentifierRenamer in the option 'urc'
(e.g. -urc=com.example.MyRenamer) to Fernflower. The class must be available on the application classpath.

The meaning of each method should be clear from naming: toBeRenamed determine whether the element will be renamed, while the other three
provide new names for classes, methods and fields respectively.  
