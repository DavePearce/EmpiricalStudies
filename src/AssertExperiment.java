import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * The purpose of this class is to reproduce a simple experiment measuring the
 * effect of assertions on commits. Specifically, whether or not methods
 * containing asserts have (relatively speaking) fewer or greater "bug fixing"
 * commits.
 * 
 * @author David J. Pearce
 *
 */
public class AssertExperiment {

	private static String[] repositories = { "file:///Users/djp/projects/Jasm/" };

	public static void main(String[] args) {
		DisplayLog log = new DisplayLog(System.out);

		try {
			for (String repo : repositories) {
				Git git = cloneGitRepository(repo, log);
				List<RevCommit> fixes = extractBugFixCommits(git, log);
				classifyCommits(fixes, git, log);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
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
	private static Git cloneGitRepository(String URI, DisplayLog log)
			throws IOException, InvalidRemoteException, TransportException, GitAPIException {
		log.startActivity("Cloning repository " + URI);
		// Create a new temporary directory
		File tmpdir = File.createTempFile("tmp", "");
		// Delete that directory, should it already exist.
		tmpdir.delete();
		// Clone the repository into the given directory
		Git git = Git.cloneRepository().setURI(URI).setDirectory(tmpdir).call();
		// Done
		log.endActivity();
		return git;
	}

	/**
	 * Traverse all commits in this repository and extract those which are
	 * considered to be "bug fixes". These are commits with the word "fix" in
	 * their short message.
	 * 
	 * @param git
	 * @param log
	 * @throws NoHeadException
	 * @throws GitAPIException
	 */
	public static List<RevCommit> extractBugFixCommits(Git git, DisplayLog log)
			throws NoHeadException, GitAPIException {
		log.startActivity("Extracting commits");
		// Get the log of all commits
		Iterable<RevCommit> revlog = git.log().call();
		// Iterate and classify each commit
		ArrayList<RevCommit> commits = new ArrayList<RevCommit>();
		for (RevCommit rc : revlog) {
			if (isBugFixCommit(rc)) {
				commits.add(rc);
			}
		}
		// Done
		log.endActivity();
		return commits;
	}

	/**
	 * Determine whether or not a given commit is a "bug fix" or not. That is,
	 * whether or not it contains the word "fix" in its short message.
	 * 
	 * @param rc
	 * @return
	 */
	private static boolean isBugFixCommit(RevCommit rc) {
		String msg = rc.getShortMessage().toLowerCase();
		return msg.contains("fix");
	}

	/**
	 * Classify a given list of commits. This requires extracting the diffs for
	 * each commit, parsing the affected Java source files (if any), locating
	 * the enclosing method (if any) and determining whether or not that method
	 * includes an instance of the assert statement.
	 * 
	 * @param commits
	 * @param git
	 * @param log
	 * @throws IncorrectObjectTypeException
	 * @throws IOException
	 * @throws GitAPIException
	 */
	private static void classifyCommits(List<RevCommit> commits, Git git, DisplayLog log)
			throws IncorrectObjectTypeException, IOException, GitAPIException {
		log.startActivity("Classifying bug fixes");		
		//
		for (RevCommit rc : commits) {
			// Extract the diffs
			List<DiffEntry> diffs = extractDiffs(rc, git, log);
			// Parse related source files into a cache
			Map<String, CompilationUnit> cache = parseSourceFiles(diffs, git);
			// Extract all hunks
			List<HunkHeader> hunks = extractHunks(diffs, git);
			// Determine list of all methods affected by diff
			List<MethodDeclaration> methods = determineAffectedMethods(hunks, cache);
		}
		log.endActivity();
	}

	/**
	 * Extract the list of diffs that a given commit represents.
	 * 
	 * @param commit
	 * @param git
	 * @param log
	 * @return
	 * @throws IncorrectObjectTypeException
	 * @throws IOException
	 * @throws GitAPIException
	 */
	private static List<DiffEntry> extractDiffs(RevCommit commit, Git git, DisplayLog log)
			throws IncorrectObjectTypeException, IOException, GitAPIException {
		ArrayList<DiffEntry> diffs = new ArrayList<DiffEntry>();
		Repository repository = git.getRepository();
		RevTree rt = commit.getTree();
		for (RevCommit parent : commit.getParents()) {
			try (ObjectReader reader = repository.newObjectReader()) {
				CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
				oldTreeIter.reset(reader, parent.getTree().getId());
				CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
				newTreeIter.reset(reader, rt.getId());
				diffs.addAll(git.diff().setNewTree(newTreeIter).setOldTree(oldTreeIter).call());
			}
		}
		return diffs;
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

		HashMap<String, CompilationUnit> units = new HashMap<String, CompilationUnit>();
		for (DiffEntry diff : diffs) {
			String newPath = diff.getNewPath();
			if (newPath.endsWith(".java") && !units.containsKey(newPath)) {
				// Source file not parsed before, so parse it
				CompilationUnit unit = parseCompilationUnit(diff.getNewId().toObjectId(),git);
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
	public static List<HunkHeader> extractHunks(List<DiffEntry> diffs, Git git) throws CorruptObjectException, MissingObjectException, IOException {
		ArrayList<HunkHeader> hunks = new ArrayList<HunkHeader>();
		// Configure the diff formatter.  This is kinda ugly!
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
		try {
			return JavaParser.parse(bin);
		} catch (ParseException e) {
			return null;
		}
	}
	
	/**
	 * For each change determine which methods (if any) enclose it.
	 * 
	 * @param hunks
	 * @param cache
	 * @return
	 */
	private static List<MethodDeclaration> determineAffectedMethods(List<HunkHeader> hunks, Map<String,CompilationUnit> cache) {
		ArrayList<MethodDeclaration> methods = new ArrayList<MethodDeclaration>();
		for(HunkHeader hunk : hunks) {
			CompilationUnit unit = cache.get(hunk.getFileHeader().getNewPath());
			// Check whether this compilation unit exists. It might not if there
			// was a problem parsing it.
			if (unit != null) {
				MethodDeclaration method = findEnclosingMethod(hunk, unit);
				if (method != null) {
					methods.add(method);
				}
			}
		}
		return methods;
	}
	
	/**
	 * Determine the enclosing method for a given change, or null if no such
	 * method.
	 * 
	 * @param header
	 * @param c
	 * @return
	 */
	private static MethodDeclaration findEnclosingMethod(HunkHeader header, CompilationUnit c) {
		Node n = c.getParentNode();
		return findEnclosingMethod(n,header,c);
	}
	
	private static MethodDeclaration findEnclosingMethod(Node n, HunkHeader header, CompilationUnit c) {
		// Need to do stuff here
	}
}
