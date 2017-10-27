import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.printer.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

public class LoopExtractor {

	private static String[] repositories = {
			//"file:///Users/djp/projects/Jasm/",
			"file:///Users/djp/projects/WhileyCompiler/"
			//"file:///Users/djp/projects/StaticVariableTest/"
			};

	private static class Results {
		/**
		 * Counts the total number of methods in latest revision. This is
		 * necessary to get a feeling for the proportion of methods of interest
		 * to methods.
		 */
		public int methods;
		/**
		 * Counts total number of methods of interest in latest revision. This
		 * is necessary to understand the proportion of methods of interest to
		 * normal methods. This determines what ratio of bug fix commits we
		 * might expect to affect our methods of interest.
		 */
		public int methodsOfInterest;

	}

	public static void main(String[] args) {
		try {
			PrettyPrinter printer = new PrettyPrinter();
			Results results = new Results();
			for (String repo : repositories) {
				long start = System.currentTimeMillis();
				System.out.println("Cloning repository " + repo + " ... ");
				Git git = cloneGitRepository(repo);
				System.out.println("Classifing all methods from " + repo + " ... ");
				List<MethodDeclaration> methods = extractMethods(git);
				for(MethodDeclaration method : methods) {
					if(isOfInterest(method)) {
						results.methodsOfInterest++;
						System.out.println(printer.print(method));
					}
					results.methods++;
				}

				long end = System.currentTimeMillis();
				System.out.println("Finished " + repo + " (" + (end - start) + "ms)");
			}
			System.out.println("Found " + results.methods + " method(s)");
			double ratio = ratio(results.methodsOfInterest,results.methods);
			System.out.println("Found " + results.methodsOfInterest + " method(s) of interest (" + ratio + "%)");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static double ratio(int numerator, int denominator) {
		double ratio = 10000 * ((double)numerator) / (double) denominator;
		return Math.round(ratio) / 100d;
	}

	/**
	 * Clone a given git repository from a URI into a temporary directory. The
	 * URI may refer to a remote location, such as a repository on GitHub or on
	 * BitBucket.
	 *
	 * @param URI
	 * @param log
	 * @return
	 * @throws IOException
	 * @throws InvalidRemoteException
	 * @throws TransportException
	 * @throws GitAPIException
	 */
	private static Git cloneGitRepository(String URI)
			throws IOException, InvalidRemoteException, TransportException, GitAPIException {
		// Create a new temporary directory
		File tmpdir = File.createTempFile("tmp", "");
		// Delete that directory, should it already exist.
		tmpdir.delete();
		// Clone the repository into the given directory
		Git git = Git.cloneRepository().setURI(URI).setDirectory(tmpdir).call();
		// Done
		return git;
	}

	/**
	 * Parse all source files related to a given selection of diffs. The reason
	 * for doing this is that multiple diffs may refer to the same compilation
	 * unit. Therefore, we want to ensure each source file is parsed at most
	 * once for efficiency.
	 *
	 * @param diffs
	 * @param git
	 * @return
	 * @throws IOException
	 * @throws MissingObjectException
	 */
	private static Map<String, CompilationUnit> parseSourceFiles(List<DiffEntry> diffs, Git git)
			throws MissingObjectException, IOException {
		HashMap<String, CompilationUnit> units = new HashMap<>();
		for (DiffEntry diff : diffs) {
			String newPath = diff.getNewPath();
			if (newPath.endsWith(".java") && !units.containsKey(newPath)) {
				// Source file not parsed before, so parse it
				CompilationUnit unit = parseCompilationUnit(diff.getNewId().toObjectId(), git);
				// Cache compilation unit
				units.put(newPath, unit);
			}
		}
		// done
		return units;
	}

	/**
	 * Extract the set of hunks mapped to their respective compilation units.
	 * Using these, we can then start looking through the respective source
	 * files.
	 *
	 * @param diffs
	 * @param git
	 * @return
	 * @throws IOException
	 * @throws MissingObjectException
	 * @throws CorruptObjectException
	 */
	public static List<HunkHeader> extractHunks(List<DiffEntry> diffs, Git git)
			throws CorruptObjectException, MissingObjectException, IOException {
		ArrayList<HunkHeader> hunks = new ArrayList<>();
		// Configure the diff formatter. This is kinda ugly!
		try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
			diffFormatter.setRepository(git.getRepository());
			diffFormatter.setContext(0);
			// Now, go through each diff extracting the hunk(s)
			for (DiffEntry diff : diffs) {
				FileHeader fh = diffFormatter.toFileHeader(diff);
				hunks.addAll(fh.getHunks());
			}
		}
		return hunks;
	}

	/**
	 * Parse a single Java source file into a CompilationUnit. This can fail in
	 * some ways. For example, if the parser is unable to parse the file for
	 * whatever reason (e.g. it may be invalid).
	 *
	 * @param id
	 * @param git
	 * @return
	 * @throws MissingObjectException
	 * @throws IOException
	 */
	private static CompilationUnit parseCompilationUnit(ObjectId id, Git git)
			throws MissingObjectException, IOException {
		Repository repository = git.getRepository();
		ObjectLoader loader = repository.open(id);
		byte[] bytes = loader.getBytes();
		ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
		return JavaParser.parse(bin);
	}

	/**
	 * Determine whether or not this is a "method of interest". This is a
	 * deliberately vague term, in order that it can be tweaked as we
	 * investigate further.
	 *
	 * @param method
	 * @return
	 */
	public static boolean isOfInterest(Node node) {
		return containsConditional(node) && simpleEnough(node); //containsLoop(node) && simpleEnough(node);
	}

	private static boolean simpleEnough(Node node) {
		if (!canRepresentInWhiley(node)) {
			// Too complex
			return false;
		} else {
			// Definite need to explore these node kinds
			for (Node child : node.getChildNodes()) {
				boolean r = simpleEnough(child);
				if (!r) {
					// yes, of interest
					return false;
				}
			}
			return true;
		}
	}

	private static boolean canRepresentInWhiley(Node node) {
		// Statements
		if(node instanceof ThrowStmt) {
			return false;
		}
		// Expressions
		if(node instanceof ObjectCreationExpr) {
			return false;
		}
		// Types
		if(node instanceof ClassOrInterfaceType) {
			return false;
		}
		return true;
	}

	private static boolean containsLoop(Node node) {
		if (node instanceof WhileStmt || node instanceof ForStmt) {
			// Bingo
			return true;
		} else {
			// Definite need to explore these node kinds
			for (Node child : node.getChildNodes()) {
				boolean r = containsLoop(child);
				if (r) {
					// yes, of interest
					return true;
				}
			}
		}
		// Not of interest
		return false;
	}

	private static boolean containsConditional(Node node) {
		if (node instanceof IfStmt || node instanceof SwitchStmt) {
			// Bingo
			return true;
		} else {
			// Definite need to explore these node kinds
			for (Node child : node.getChildNodes()) {
				boolean r = containsConditional(child);
				if (r) {
					// yes, of interest
					return true;
				}
			}
		}
		// Not of interest
		return false;
	}

	/**
	 * Classify all methods in all files in a given git repository.
	 *
	 * @param git
	 * @param results
	 * @throws RevisionSyntaxException
	 * @throws AmbiguousObjectException
	 * @throws IncorrectObjectTypeException
	 * @throws IOException
	 */
	private static List<MethodDeclaration> extractMethods(Git git) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		Repository repository = git.getRepository();
		//
		ArrayList<MethodDeclaration> methods = new ArrayList<>();
		// Find HEAD revision on default branch
		ObjectId head = repository.resolve(Constants.HEAD);
		// Read the tree at HEAD
		try (RevWalk revWalk = new RevWalk(repository)) {
			RevCommit commit = revWalk.parseCommit(head);
			// Use HEAD tree to recursively walk java source files
			RevTree tree = commit.getTree();
			try (TreeWalk treeWalk = new TreeWalk(repository)) {
				treeWalk.addTree(tree);
				treeWalk.setRecursive(true);
				treeWalk.setFilter(PathSuffixFilter.create(".java"));
				while(treeWalk.next()) {
					CompilationUnit cu = parseCompilationUnit(treeWalk.getObjectId(0),git);
					extractMethods(cu,methods);
				}
			}
		}
		return methods;
	}

	/**
	 * Extract all methods from a given AST node.
	 *
	 * @param node
	 * @param declaration
	 */
	private static void extractMethods(Node node, List<MethodDeclaration> methods) {
		if (node instanceof MethodDeclaration) {
			methods.add((MethodDeclaration) node);
		} else {
			for (Node child : node.getChildNodes()) {
				extractMethods(child, methods);
			}
		}
	}
}
