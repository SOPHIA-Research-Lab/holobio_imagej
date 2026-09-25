// Open a HoloBio complex-field .npy (complex64) in Fiji as Real / Imag / Amplitude.
// Fiji has no complex pixel type, so the raw re,im pairs are imported as one image twice
// as wide and split by column parity. Shape and data offset are read from the NPY header.
path = getArgument();
if (path == "") path = File.openDialog("Complex field (.npy)");
head = File.openAsRawString(path, 256);
if (!startsWith(substring(head, 1, 6), "NUMPY")) exit("Not a .npy file: " + path);
if (indexOf(head, "'<c8'") < 0) exit("Expected complex64 ('<c8'); this macro does not handle other dtypes.");
if (indexOf(head, "'fortran_order': True") >= 0) exit("Fortran-ordered arrays are not supported.");
offset = 10 + charCodeAt(head, 8) + 256 * charCodeAt(head, 9);
s = substring(head, indexOf(head, "'shape': (") + 10);
s = substring(s, 0, indexOf(s, ")"));
dims = split(s, ", ");
rows = parseInt(dims[0]); cols = parseInt(dims[1]);
name = File.getNameWithoutExtension(path);

setBatchMode(true);
run("Raw...", "open=[" + path + "] image=[32-bit Real] width=" + (2 * cols) + " height=" + rows
    + " offset=" + offset + " number=1 gap=0 little-endian");
rename("interleaved");
run("Duplicate...", "title=[" + name + " Real]");
run("Size...", "width=" + cols + " height=" + rows + " depth=1 interpolation=None");
selectImage("interleaved");
run("Translate...", "x=-1 y=0 interpolation=None");
run("Size...", "width=" + cols + " height=" + rows + " depth=1 interpolation=None");
rename(name + " Imag");
imageCalculator("Multiply create 32-bit", name + " Real", name + " Real"); rename("re2");
imageCalculator("Multiply create 32-bit", name + " Imag", name + " Imag"); rename("im2");
imageCalculator("Add create 32-bit", "re2", "im2"); rename(name + " Amplitude");
run("Square Root");
close("re2"); close("im2");
setBatchMode("exit and display");
